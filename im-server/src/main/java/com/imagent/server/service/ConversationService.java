package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.ConvUpdatePayload;
import com.imagent.server.dto.ConversationMemberVO;
import com.imagent.server.dto.ConversationVO;
import com.imagent.server.dto.CreateGroupConvRequest;
import com.imagent.server.entity.ImConversation;
import com.imagent.server.entity.ImConversationMember;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImConversationMemberMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final Duration MEMBERS_CACHE_TTL = Duration.ofMinutes(10);

    private final ImConversationMapper conversationMapper;
    private final ImConversationMemberMapper memberMapper;
    private final ImUserMapper userMapper;
    private final UserService userService;
    private final FriendService friendService;
    private final UnreadService unreadService;
    private final MessagePushHelper pushHelper;
    private final StringRedisTemplate redisTemplate;

    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createOrGetDirectConversation(Long currentUserId, Long targetUserId) {
        if (currentUserId.equals(targetUserId)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不能和自己创建单聊");
        }
        ImUser targetUser = userMapper.selectById(targetUserId);
        if (targetUser == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标用户不存在");
        }
        friendService.assertCanDirectChat(currentUserId, targetUserId);

        long minUid = Math.min(currentUserId, targetUserId);
        long maxUid = Math.max(currentUserId, targetUserId);
        String bizKey = "direct:" + minUid + ":" + maxUid;

        ImConversation existing = conversationMapper.selectOne(new LambdaQueryWrapper<ImConversation>()
                .eq(ImConversation::getBizKey, bizKey));
        if (existing != null) {
            return toVO(existing, targetUser.getNickname());
        }

        ImConversation conv = new ImConversation();
        conv.setType(1);
        conv.setBizKey(bizKey);
        conv.setTitle("");
        conv.setCurrentSeq(0L);
        conv.setLastMsgPreview("");
        conversationMapper.insert(conv);

        addMember(conv.getId(), currentUserId, 1);
        addMember(conv.getId(), targetUserId, 1);
        redisTemplate.delete(RedisKeys.conversationMembers(conv.getId()));
        return toVO(conv, targetUser.getNickname());
    }

    @Transactional(rollbackFor = Exception.class)
    public ConversationVO createGroupConversation(Long ownerId, CreateGroupConvRequest req) {
        Set<Long> uniqueMembers = new LinkedHashSet<>(req.getMemberIds());
        uniqueMembers.remove(ownerId);
        if (uniqueMembers.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请至少选择一名其他成员建群");
        }
        List<ImUser> existingUsers = userMapper.selectBatchIds(uniqueMembers);
        if (existingUsers.size() != uniqueMembers.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "部分群成员用户不存在");
        }
        friendService.assertAllFriends(ownerId, uniqueMembers);

        ImConversation conv = new ImConversation();
        conv.setType(2);
        conv.setTitle(req.getTitle().trim());
        conv.setOwnerId(ownerId);
        conv.setCurrentSeq(0L);
        conv.setLastMsgPreview("");
        conversationMapper.insert(conv);

        addMember(conv.getId(), ownerId, 3);
        for (Long memberId : uniqueMembers) {
            addMember(conv.getId(), memberId, 1);
        }
        redisTemplate.delete(RedisKeys.conversationMembers(conv.getId()));
        return toVO(conv, conv.getTitle());
    }

    public List<ConversationVO> listMyConversations(Long userId) {
        List<ImConversationMember> memberships = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>().eq(ImConversationMember::getUserId, userId));
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> convIds = memberships.stream().map(ImConversationMember::getConversationId).toList();
        List<ImConversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<ImConversation>()
                        .in(ImConversation::getId, convIds));

        List<Long> directConvIds = conversations.stream()
                .filter(c -> Integer.valueOf(1).equals(c.getType()))
                .map(ImConversation::getId)
                .toList();

        Map<Long, ImUser> directPeerMap = resolveDirectPeers(directConvIds, userId);
        Map<Long, Boolean> peerOnlineMap = resolveOnlineFlags(
                directPeerMap.values().stream().map(ImUser::getId).distinct().toList());

        Map<Long, ImConversationMember> memberMap = memberships.stream()
                .collect(Collectors.toMap(ImConversationMember::getConversationId, m -> m, (a, b) -> a));

        // 一次批量查成员计数
        Map<Long, Integer> countMap = new HashMap<>();
        List<ImConversationMember> allConvMembers = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>()
                        .in(ImConversationMember::getConversationId, convIds)
                        .select(ImConversationMember::getConversationId));
        for (ImConversationMember m : allConvMembers) {
            countMap.merge(m.getConversationId(), 1, Integer::sum);
        }

        Map<Long, Long> redisUnreadMap = unreadService.getUserUnreadMap(userId);
        Map<Long, Long> mentionMap = unreadService.getUserMentionMap(userId);

        List<ConversationVO> result = new ArrayList<>(conversations.size());
        for (ImConversation conv : conversations) {
            boolean isDirect = Integer.valueOf(1).equals(conv.getType());
            ImUser peer = isDirect ? directPeerMap.get(conv.getId()) : null;
            String displayTitle = isDirect
                    ? (peer != null ? peer.getNickname() : "单聊会话")
                    : conv.getTitle();
            Long peerUserId = peer != null ? peer.getId() : null;
            String peerAvatar = peer != null ? peer.getAvatar() : null;
            Boolean peerOnline = peerUserId != null ? peerOnlineMap.getOrDefault(peerUserId, false) : null;
            String peerStatusEmoji = peer != null ? peer.getStatusEmoji() : null;
            String peerStatusText = peer != null ? peer.getStatusText() : null;

            ImConversationMember self = memberMap.get(conv.getId());
            long lastRead = (self != null && self.getLastReadSeq() != null) ? self.getLastReadSeq() : 0L;
            boolean pinned = self != null && Integer.valueOf(1).equals(self.getPinned());
            boolean muted = self != null && Integer.valueOf(1).equals(self.getMuted());
            long currentSeq = conv.getCurrentSeq() == null ? 0L : conv.getCurrentSeq();
            long unread = redisUnreadMap.getOrDefault(conv.getId(), Math.max(0L, currentSeq - lastRead));
            Long mentionSeq = mentionMap.get(conv.getId());
            int memberCount = countMap.getOrDefault(conv.getId(), isDirect ? 2 : 1);

            result.add(toVO(conv, displayTitle, lastRead, unread, mentionSeq, pinned, muted,
                    memberCount, peerUserId, peerAvatar, peerOnline, peerStatusEmoji, peerStatusText));
        }

        // 微信/飞书排序：置顶会话优先，其次按最近消息时间倒序
        result.sort(Comparator
                .comparing((ConversationVO c) -> Boolean.TRUE.equals(c.getPinned())).reversed()
                .thenComparing(c -> c.getLastMsgTime() != null ? c.getLastMsgTime() : 0L, Comparator.reverseOrder()));
        return result;
    }

    /**
     * 更新个人会话偏好：置顶 / 免打扰
     */
    public void updatePreferences(Long userId, Long convId, Boolean pinned, Boolean muted) {
        if (!isMember(convId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        LambdaUpdateWrapper<ImConversationMember> wrapper = new LambdaUpdateWrapper<ImConversationMember>()
                .eq(ImConversationMember::getConversationId, convId)
                .eq(ImConversationMember::getUserId, userId);
        boolean hasUpdate = false;
        if (pinned != null) {
            wrapper.set(ImConversationMember::getPinned, pinned ? 1 : 0);
            hasUpdate = true;
        }
        if (muted != null) {
            wrapper.set(ImConversationMember::getMuted, muted ? 1 : 0);
            hasUpdate = true;
        }
        if (hasUpdate) {
            memberMapper.update(null, wrapper);
        }
    }

    /**
     * 查询会话完整成员列表（附带在线绿点状态与实时已读水位，同时支撑：飞书已读进度圈、@联想选人、群管理）
     */
    public List<ConversationMemberVO> listConversationMembers(Long userId, Long convId) {
        if (!isMember(convId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        List<ImConversationMember> rows = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>()
                        .eq(ImConversationMember::getConversationId, convId)
                        .orderByDesc(ImConversationMember::getRole)
                        .orderByAsc(ImConversationMember::getJoinedAt));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> userIds = rows.stream().map(ImConversationMember::getUserId).toList();
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));
        Map<Long, Boolean> onlineMap = resolveOnlineFlags(userIds);
        Map<Long, Long> redisReadMap = unreadService.getConversationReadWatermarks(convId);

        List<ConversationMemberVO> result = new ArrayList<>(rows.size());
        for (ImConversationMember m : rows) {
            ImUser u = userMap.get(m.getUserId());
            long dbSeq = m.getLastReadSeq() == null ? 0L : m.getLastReadSeq();
            long cachedSeq = redisReadMap.getOrDefault(m.getUserId(), 0L);
            long effectiveSeq = Math.max(dbSeq, cachedSeq);
            result.add(ConversationMemberVO.builder()
                    .userId(m.getUserId())
                    .username(u != null ? u.getUsername() : "user" + m.getUserId())
                    .nickname(u != null ? u.getNickname() : "用户" + m.getUserId())
                    .avatar(u != null ? u.getAvatar() : "")
                    .signature(u != null ? u.getSignature() : "")
                    .statusEmoji(u != null ? u.getStatusEmoji() : "")
                    .statusText(u != null ? u.getStatusText() : "")
                    .role(m.getRole() == null ? 1 : m.getRole())
                    .online(onlineMap.getOrDefault(m.getUserId(), false))
                    .lastReadSeq(effectiveSeq)
                    .build());
        }
        return result;
    }

    /**
     * 群聊邀请新成员（更新 DB 后主动失效 Redis Cache-Aside 成员缓存，并向全群推送 CONV_UPDATE）
     */
    @Transactional(rollbackFor = Exception.class)
    public String inviteGroupMembers(Long operatorId, Long convId, List<Long> newMemberIds) {
        ImConversation conv = requireGroupConversation(convId);
        if (!isMember(convId, operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群成员");
        }
        List<Long> currentIds = listMemberIds(convId);
        Set<Long> toAdd = new LinkedHashSet<>();
        for (Long uid : newMemberIds) {
            if (uid != null && !currentIds.contains(uid)) {
                toAdd.add(uid);
            }
        }
        if (toAdd.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "所选用户均已在群聊中");
        }
        List<ImUser> users = userMapper.selectBatchIds(toAdd);
        if (users.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "未找到有效用户");
        }
        friendService.assertAllFriends(operatorId, toAdd);
        for (ImUser u : users) {
            addMember(convId, u.getId(), 1);
        }
        // Cache-Aside 精准失效会话成员缓存
        redisTemplate.delete(RedisKeys.conversationMembers(convId));

        String operatorNick = userService.getNicknameCached(operatorId);
        String addedNames = users.stream().map(ImUser::getNickname).collect(Collectors.joining("、"));
        List<Long> allMembers = new ArrayList<>(currentIds);
        users.forEach(u -> allMembers.add(u.getId()));
        pushHelper.pushAsync(allMembers, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("MEMBERS_CHANGED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));
        return operatorNick + " 邀请了 " + addedNames + " 加入群聊";
    }

    /**
     * 踢出群成员或主动退群（群主退群时自动将群主移交给最早加入的剩余成员）
     */
    @Transactional(rollbackFor = Exception.class)
    public String removeGroupMember(Long operatorId, Long convId, Long targetUserId) {
        ImConversation conv = requireGroupConversation(convId);
        List<ImConversationMember> allRows = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>()
                        .eq(ImConversationMember::getConversationId, convId)
                        .orderByAsc(ImConversationMember::getJoinedAt));

        ImConversationMember operator = allRows.stream()
                .filter(m -> operatorId.equals(m.getUserId())).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.FORBIDDEN, "你不是该群成员"));
        ImConversationMember target = allRows.stream()
                .filter(m -> targetUserId.equals(m.getUserId())).findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "目标成员不在该群中"));

        boolean selfLeave = operatorId.equals(targetUserId);
        if (!selfLeave && !Integer.valueOf(3).equals(operator.getRole())) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅群主可移出其他成员");
        }

        List<Long> notifyMembers = allRows.stream().map(ImConversationMember::getUserId).toList();
        memberMapper.deleteById(target.getId());

        // 若群主主动退群且群内还有其他成员，自动顺延移交群主
        if (selfLeave && Integer.valueOf(3).equals(target.getRole())) {
            allRows.stream()
                    .filter(m -> !targetUserId.equals(m.getUserId()))
                    .findFirst()
                    .ifPresent(nextOwner -> {
                        memberMapper.update(null, new LambdaUpdateWrapper<ImConversationMember>()
                                .eq(ImConversationMember::getId, nextOwner.getId())
                                .set(ImConversationMember::getRole, 3));
                        ImConversation convUpd = new ImConversation();
                        convUpd.setId(convId);
                        convUpd.setOwnerId(nextOwner.getUserId());
                        conversationMapper.updateById(convUpd);
                    });
        }

        redisTemplate.delete(RedisKeys.conversationMembers(convId));
        unreadService.removeMemberState(convId, targetUserId);

        String operatorNick = userService.getNicknameCached(operatorId);
        String targetNick = userService.getNicknameCached(targetUserId);
        pushHelper.pushAsync(notifyMembers, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("MEMBERS_CHANGED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));

        return selfLeave ? (targetNick + " 退出了群聊") : (operatorNick + " 将 " + targetNick + " 移出了群聊");
    }

    /**
     * 修改群名称并向全群推送 CONV_UPDATE
     */
    @Transactional(rollbackFor = Exception.class)
    public String renameGroup(Long operatorId, Long convId, String rawTitle) {
        requireGroupConversation(convId);
        List<Long> memberIds = listMemberIds(convId);
        if (!memberIds.contains(operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群成员");
        }
        String cleanTitle = rawTitle.trim();
        ImConversation update = new ImConversation();
        update.setId(convId);
        update.setTitle(cleanTitle);
        conversationMapper.updateById(update);

        String operatorNick = userService.getNicknameCached(operatorId);
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("RENAMED")
                .title(cleanTitle)
                .operatorNickname(operatorNick)
                .build()));
        return operatorNick + " 将群名称修改为「" + cleanTitle + "」";
    }

    public boolean isMember(Long conversationId, Long userId) {
        return listMemberIds(conversationId).contains(userId);
    }

    /**
     * 热路径会话成员查询（Redis Set Cache-Aside，将高频校验与群推送成员查询从 MySQL 卸载至 Redis）
     */
    public List<Long> listMemberIds(Long conversationId) {
        String key = RedisKeys.conversationMembers(conversationId);
        Set<String> cached = redisTemplate.opsForSet().members(key);
        if (cached != null && !cached.isEmpty()) {
            return cached.stream().map(Long::valueOf).toList();
        }
        List<Long> dbMembers = memberMapper.selectList(new LambdaQueryWrapper<ImConversationMember>()
                        .eq(ImConversationMember::getConversationId, conversationId))
                .stream()
                .map(ImConversationMember::getUserId)
                .toList();
        if (!dbMembers.isEmpty()) {
            String[] values = dbMembers.stream().map(String::valueOf).toArray(String[]::new);
            redisTemplate.opsForSet().add(key, values);
            redisTemplate.expire(key, MEMBERS_CACHE_TTL);
        }
        return dbMembers;
    }

    private ImConversation requireGroupConversation(Long convId) {
        ImConversation conv = conversationMapper.selectById(convId);
        if (conv == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!Integer.valueOf(2).equals(conv.getType())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该操作仅适用于群聊");
        }
        return conv;
    }

    private Map<Long, ImUser> resolveDirectPeers(List<Long> directConvIds, Long currentUserId) {
        if (directConvIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ImConversationMember> peerMembers = memberMapper.selectList(
                new LambdaQueryWrapper<ImConversationMember>()
                        .in(ImConversationMember::getConversationId, directConvIds)
                        .ne(ImConversationMember::getUserId, currentUserId));
        if (peerMembers.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> peerIds = peerMembers.stream().map(ImConversationMember::getUserId).distinct().toList();
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(peerIds).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));

        Map<Long, ImUser> result = new HashMap<>();
        for (ImConversationMember m : peerMembers) {
            ImUser u = userMap.get(m.getUserId());
            if (u != null) {
                result.putIfAbsent(m.getConversationId(), u);
            }
        }
        return result;
    }

    private Map<Long, Boolean> resolveOnlineFlags(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = userIds.stream().map(RedisKeys::userRoute).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, Boolean> map = new HashMap<>(userIds.size());
        for (int i = 0; i < userIds.size(); i++) {
            String route = (values != null && i < values.size()) ? values.get(i) : null;
            map.put(userIds.get(i), route != null && !route.isBlank());
        }
        return map;
    }

    private void addMember(Long convId, Long userId, int role) {
        ImConversationMember member = new ImConversationMember();
        member.setConversationId(convId);
        member.setUserId(userId);
        member.setRole(role);
        member.setLastReadSeq(0L);
        member.setMuted(0);
        member.setPinned(0);
        memberMapper.insert(member);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle) {
        return toVO(conv, displayTitle, 0L, 0L, null, false, false, 2, null, null, null, null, null);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle, long lastReadSeq,
                                long unreadCount, Long mentionSeq, boolean pinned, boolean muted,
                                int memberCount, Long peerUserId, String peerAvatar, Boolean peerOnline,
                                String peerStatusEmoji, String peerStatusText) {
        Long lastTime = conv.getLastMsgTime() != null
                ? conv.getLastMsgTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        return ConversationVO.builder()
                .id(conv.getId())
                .type(conv.getType())
                .title(displayTitle)
                .ownerId(conv.getOwnerId())
                .currentSeq(conv.getCurrentSeq())
                .lastReadSeq(lastReadSeq)
                .unreadCount(unreadCount)
                .mentionSeq(mentionSeq)
                .pinned(pinned)
                .muted(muted)
                .memberCount(memberCount)
                .peerUserId(peerUserId)
                .peerAvatar(peerAvatar)
                .peerOnline(peerOnline)
                .peerStatusEmoji(peerStatusEmoji)
                .peerStatusText(peerStatusText)
                .lastMsgPreview(conv.getLastMsgPreview())
                .lastMsgTime(lastTime)
                .build();
    }
}
