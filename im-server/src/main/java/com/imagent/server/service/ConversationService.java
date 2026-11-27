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
import com.imagent.server.dto.GroupJoinRequestVO;
import com.imagent.server.dto.InviteMembersResultVO;
import com.imagent.server.dto.UpdateConvPrefsRequest;
import com.imagent.server.entity.ImConversation;
import com.imagent.server.entity.ImConversationMember;
import com.imagent.server.entity.ImGroupJoinRequest;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImConversationMemberMapper;
import com.imagent.server.mapper.ImGroupJoinRequestMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private final ImGroupJoinRequestMapper joinRequestMapper;
    private final ImUserMapper userMapper;
    private final UserService userService;
    private final FriendService friendService;
    private final UnreadService unreadService;
    private final MessagePushHelper pushHelper;
    private final StringRedisTemplate redisTemplate;

    public record InviteGroupOutcome(InviteMembersResultVO result, String systemNotice) {
    }

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
        conv.setJoinApprovalRequired(0);
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
        conv.setJoinApprovalRequired(0);
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

        // 批量解析群公告发布人昵称
        Set<Long> publisherIds = conversations.stream()
                .map(ImConversation::getAnnouncementUpdatedBy)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> publisherNickMap = new HashMap<>();
        if (!publisherIds.isEmpty()) {
            for (ImUser u : userMapper.selectBatchIds(publisherIds)) {
                publisherNickMap.put(u.getId(), u.getNickname());
            }
        }

        // 批量统计本人作为群主/管理员的群待审核入群申请数
        List<Long> adminGroupIds = conversations.stream()
                .filter(c -> Integer.valueOf(2).equals(c.getType()))
                .map(ImConversation::getId)
                .filter(cid -> {
                    ImConversationMember self = memberMap.get(cid);
                    return self != null && self.getRole() != null && self.getRole() >= 2;
                })
                .toList();
        Map<Long, Integer> pendingJoinCountMap = new HashMap<>();
        if (!adminGroupIds.isEmpty()) {
            List<ImGroupJoinRequest> pendingReqs = joinRequestMapper.selectList(
                    new LambdaQueryWrapper<ImGroupJoinRequest>()
                            .in(ImGroupJoinRequest::getConversationId, adminGroupIds)
                            .eq(ImGroupJoinRequest::getStatus, "PENDING")
                            .select(ImGroupJoinRequest::getConversationId));
            for (ImGroupJoinRequest r : pendingReqs) {
                pendingJoinCountMap.merge(r.getConversationId(), 1, Integer::sum);
            }
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
            String groupRemark = (self != null && self.getGroupRemark() != null) ? self.getGroupRemark() : "";
            String myGroupNickname = (self != null && self.getGroupNickname() != null) ? self.getGroupNickname() : "";
            boolean showMemberNickname = self == null || !Integer.valueOf(0).equals(self.getShowMemberNickname());
            int pendingJoinCount = pendingJoinCountMap.getOrDefault(conv.getId(), 0);
            String publisherNick = conv.getAnnouncementUpdatedBy() != null
                    ? publisherNickMap.getOrDefault(conv.getAnnouncementUpdatedBy(), "")
                    : "";

            long currentSeq = conv.getCurrentSeq() == null ? 0L : conv.getCurrentSeq();
            long unread = redisUnreadMap.getOrDefault(conv.getId(), Math.max(0L, currentSeq - lastRead));
            Long mentionSeq = mentionMap.get(conv.getId());
            int memberCount = countMap.getOrDefault(conv.getId(), isDirect ? 2 : 1);

            result.add(toVO(conv, displayTitle, groupRemark, publisherNick, myGroupNickname,
                    showMemberNickname, pendingJoinCount, lastRead, unread, mentionSeq, pinned, muted,
                    memberCount, peerUserId, peerAvatar, peerOnline, peerStatusEmoji, peerStatusText));
        }

        // 微信/飞书排序：置顶会话优先，其次按最近消息时间倒序
        result.sort(Comparator
                .comparing((ConversationVO c) -> Boolean.TRUE.equals(c.getPinned())).reversed()
                .thenComparing(c -> c.getLastMsgTime() != null ? c.getLastMsgTime() : 0L, Comparator.reverseOrder()));
        return result;
    }

    /**
     * 更新个人会话偏好：置顶 / 免打扰 / 群备注（仅自己可见）/ 我在本群的昵称 / 显示群成员昵称
     */
    public void updatePreferences(Long userId, Long convId, UpdateConvPrefsRequest request) {
        if (!isMember(convId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员");
        }
        if (request == null) {
            return;
        }
        LambdaUpdateWrapper<ImConversationMember> wrapper = new LambdaUpdateWrapper<ImConversationMember>()
                .eq(ImConversationMember::getConversationId, convId)
                .eq(ImConversationMember::getUserId, userId);
        boolean hasUpdate = false;
        boolean groupNicknameChanged = false;

        if (request.getPinned() != null) {
            wrapper.set(ImConversationMember::getPinned, request.getPinned() ? 1 : 0);
            hasUpdate = true;
        }
        if (request.getMuted() != null) {
            wrapper.set(ImConversationMember::getMuted, request.getMuted() ? 1 : 0);
            hasUpdate = true;
        }
        if (request.getGroupRemark() != null) {
            String remark = request.getGroupRemark().trim();
            if (remark.length() > 64) {
                remark = remark.substring(0, 64);
            }
            wrapper.set(ImConversationMember::getGroupRemark, remark);
            hasUpdate = true;
        }
        if (request.getGroupNickname() != null) {
            String gNick = request.getGroupNickname().trim();
            if (gNick.length() > 64) {
                gNick = gNick.substring(0, 64);
            }
            wrapper.set(ImConversationMember::getGroupNickname, gNick);
            hasUpdate = true;
            groupNicknameChanged = true;
        }
        if (request.getShowMemberNickname() != null) {
            wrapper.set(ImConversationMember::getShowMemberNickname, request.getShowMemberNickname() ? 1 : 0);
            hasUpdate = true;
        }

        if (hasUpdate) {
            memberMapper.update(null, wrapper);
        }

        if (groupNicknameChanged) {
            ImConversation conv = conversationMapper.selectById(convId);
            if (conv != null && Integer.valueOf(2).equals(conv.getType())) {
                List<Long> memberIds = listMemberIds(convId);
                String operatorNick = userService.getNicknameCached(userId);
                pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                        .conversationId(convId)
                        .eventType("MEMBERS_CHANGED")
                        .title(conv.getTitle())
                        .operatorNickname(operatorNick)
                        .build()));
            }
        }
    }

    /**
     * 兼容旧签名的偏好更新方法
     */
    public void updatePreferences(Long userId, Long convId, Boolean pinned, Boolean muted) {
        UpdateConvPrefsRequest req = new UpdateConvPrefsRequest();
        req.setPinned(pinned);
        req.setMuted(muted);
        updatePreferences(userId, convId, req);
    }

    /**
     * 发布或清空群公告（仅群主/群管理员可操作，发布后推送全员通知）
     */
    @Transactional(rollbackFor = Exception.class)
    public String updateGroupAnnouncement(Long operatorId, Long convId, String rawAnnouncement) {
        ImConversation conv = requireGroupConversation(convId);
        requireGroupAdminOrOwner(convId, operatorId, "仅群主或群管理员可编辑群公告");

        String cleaned = rawAnnouncement == null ? "" : rawAnnouncement.trim();
        LocalDateTime now = LocalDateTime.now();
        conversationMapper.update(null, new LambdaUpdateWrapper<ImConversation>()
                .eq(ImConversation::getId, convId)
                .set(ImConversation::getAnnouncement, cleaned.isEmpty() ? null : cleaned)
                .set(ImConversation::getAnnouncementUpdatedAt, cleaned.isEmpty() ? null : now)
                .set(ImConversation::getAnnouncementUpdatedBy, cleaned.isEmpty() ? null : operatorId));

        List<Long> memberIds = listMemberIds(convId);
        String operatorNick = userService.getNicknameCached(operatorId);
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("ANNOUNCEMENT_UPDATED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));

        if (cleaned.isEmpty()) {
            return operatorNick + " 清空了群公告";
        }
        String preview = cleaned.length() > 120 ? cleaned.substring(0, 120) + "..." : cleaned;
        return operatorNick + " 发布了群公告：" + preview;
    }

    /**
     * 更新群安全设置（进群是否需要群主/群管理员确认，仅群主/管理员可切换）
     */
    @Transactional(rollbackFor = Exception.class)
    public String updateGroupSettings(Long operatorId, Long convId, Boolean joinApprovalRequired) {
        ImConversation conv = requireGroupConversation(convId);
        requireGroupAdminOrOwner(convId, operatorId, "仅群主或群管理员可修改群验证设置");
        if (joinApprovalRequired == null) {
            return null;
        }
        int flag = joinApprovalRequired ? 1 : 0;
        if (Integer.valueOf(flag).equals(conv.getJoinApprovalRequired())) {
            return null;
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<ImConversation>()
                .eq(ImConversation::getId, convId)
                .set(ImConversation::getJoinApprovalRequired, flag));

        List<Long> memberIds = listMemberIds(convId);
        String operatorNick = userService.getNicknameCached(operatorId);
        pushHelper.pushAsync(memberIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("SETTINGS_UPDATED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));

        return operatorNick + (joinApprovalRequired
                ? " 开启了「进群需要群主/群管理员确认」"
                : " 关闭了「进群需要群主/群管理员确认」");
    }

    /**
     * 查询会话完整成员列表（附带群内昵称、在线绿点状态与实时已读水位）
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
                    .groupNickname(m.getGroupNickname() != null ? m.getGroupNickname() : "")
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
     * 群聊邀请新成员：
     * - 未开启进群确认，或操作人为群主/管理员：直接入群并通知全群；
     * - 开启了“进群需要群主/群管理员确认”且操作人为普通成员：生成待审核入群申请并通知群主/管理员。
     */
    @Transactional(rollbackFor = Exception.class)
    public InviteGroupOutcome inviteGroupMembers(Long operatorId, Long convId, List<Long> newMemberIds) {
        ImConversation conv = requireGroupConversation(convId);
        ImConversationMember operator = memberMapper.selectOne(new LambdaQueryWrapper<ImConversationMember>()
                .eq(ImConversationMember::getConversationId, convId)
                .eq(ImConversationMember::getUserId, operatorId));
        if (operator == null) {
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

        boolean needApproval = Integer.valueOf(1).equals(conv.getJoinApprovalRequired())
                && (operator.getRole() == null || operator.getRole() < 2);
        String operatorNick = userService.getNicknameCached(operatorId);

        if (needApproval) {
            Set<Long> existingPendingInvitees = joinRequestMapper.selectList(
                            new LambdaQueryWrapper<ImGroupJoinRequest>()
                                    .eq(ImGroupJoinRequest::getConversationId, convId)
                                    .eq(ImGroupJoinRequest::getStatus, "PENDING")
                                    .in(ImGroupJoinRequest::getInviteeId, toAdd))
                    .stream()
                    .map(ImGroupJoinRequest::getInviteeId)
                    .collect(Collectors.toSet());

            int created = 0;
            for (ImUser u : users) {
                if (existingPendingInvitees.contains(u.getId())) {
                    continue;
                }
                ImGroupJoinRequest req = new ImGroupJoinRequest();
                req.setConversationId(convId);
                req.setInviterId(operatorId);
                req.setInviteeId(u.getId());
                req.setStatus("PENDING");
                joinRequestMapper.insert(req);
                created++;
            }

            List<Long> adminIds = listAdminAndOwnerIds(convId);
            if (!adminIds.isEmpty()) {
                pushHelper.pushAsync(adminIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                        .conversationId(convId)
                        .eventType("JOIN_REQUEST_CREATED")
                        .title(conv.getTitle())
                        .operatorNickname(operatorNick)
                        .build()));
            }
            String msg = created > 0
                    ? "已提交入群申请，等待群主或群管理员确认"
                    : "所选好友已有待确认的入群申请，请等待群主或群管理员审核";
            return new InviteGroupOutcome(
                    InviteMembersResultVO.builder()
                            .joinedCount(0)
                            .pendingApprovalCount(Math.max(created, existingPendingInvitees.size()))
                            .message(msg)
                            .build(),
                    null
            );
        }

        for (ImUser u : users) {
            addMember(convId, u.getId(), 1);
        }
        // Cache-Aside 精准失效会话成员缓存
        redisTemplate.delete(RedisKeys.conversationMembers(convId));

        String addedNames = users.stream().map(ImUser::getNickname).collect(Collectors.joining("、"));
        List<Long> allMembers = new ArrayList<>(currentIds);
        users.forEach(u -> allMembers.add(u.getId()));
        pushHelper.pushAsync(allMembers, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("MEMBERS_CHANGED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));

        return new InviteGroupOutcome(
                InviteMembersResultVO.builder()
                        .joinedCount(users.size())
                        .pendingApprovalCount(0)
                        .message("已邀请 " + addedNames + " 加入群聊")
                        .build(),
                operatorNick + " 邀请了 " + addedNames + " 加入群聊"
        );
    }

    /**
     * 查看群聊待审核入群申请（仅群主/群管理员可查看）
     */
    public List<GroupJoinRequestVO> listPendingJoinRequests(Long operatorId, Long convId) {
        requireGroupConversation(convId);
        requireGroupAdminOrOwner(convId, operatorId, "仅群主或群管理员可查看入群申请");

        List<ImGroupJoinRequest> rows = joinRequestMapper.selectList(
                new LambdaQueryWrapper<ImGroupJoinRequest>()
                        .eq(ImGroupJoinRequest::getConversationId, convId)
                        .eq(ImGroupJoinRequest::getStatus, "PENDING")
                        .orderByDesc(ImGroupJoinRequest::getCreatedAt));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> uids = new HashSet<>();
        for (ImGroupJoinRequest r : rows) {
            uids.add(r.getInviterId());
            uids.add(r.getInviteeId());
        }
        Map<Long, ImUser> userMap = userMapper.selectBatchIds(uids).stream()
                .collect(Collectors.toMap(ImUser::getId, u -> u, (a, b) -> a));

        List<GroupJoinRequestVO> result = new ArrayList<>(rows.size());
        for (ImGroupJoinRequest r : rows) {
            ImUser inviter = userMap.get(r.getInviterId());
            ImUser invitee = userMap.get(r.getInviteeId());
            long createdMs = r.getCreatedAt() != null
                    ? r.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : System.currentTimeMillis();
            result.add(GroupJoinRequestVO.builder()
                    .id(r.getId())
                    .conversationId(r.getConversationId())
                    .inviterId(r.getInviterId())
                    .inviterNickname(inviter != null ? inviter.getNickname() : "成员" + r.getInviterId())
                    .inviteeId(r.getInviteeId())
                    .inviteeUsername(invitee != null ? invitee.getUsername() : "user" + r.getInviteeId())
                    .inviteeNickname(invitee != null ? invitee.getNickname() : "用户" + r.getInviteeId())
                    .inviteeAvatar(invitee != null ? invitee.getAvatar() : "")
                    .status(r.getStatus())
                    .createdAt(createdMs)
                    .build());
        }
        return result;
    }

    /**
     * 群主/管理员审批入群申请（同意或拒绝）
     */
    @Transactional(rollbackFor = Exception.class)
    public String reviewJoinRequest(Long operatorId, Long convId, Long requestId, boolean approve) {
        ImConversation conv = requireGroupConversation(convId);
        requireGroupAdminOrOwner(convId, operatorId, "仅群主或群管理员可审核入群申请");

        ImGroupJoinRequest req = joinRequestMapper.selectById(requestId);
        if (req == null || !convId.equals(req.getConversationId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "入群申请不存在");
        }
        if (!"PENDING".equals(req.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该入群申请已被处理");
        }

        LocalDateTime now = LocalDateTime.now();
        joinRequestMapper.update(null, new LambdaUpdateWrapper<ImGroupJoinRequest>()
                .eq(ImGroupJoinRequest::getId, requestId)
                .set(ImGroupJoinRequest::getStatus, approve ? "APPROVED" : "REJECTED")
                .set(ImGroupJoinRequest::getReviewerId, operatorId)
                .set(ImGroupJoinRequest::getReviewedAt, now));

        String operatorNick = userService.getNicknameCached(operatorId);
        if (!approve) {
            List<Long> adminIds = listAdminAndOwnerIds(convId);
            if (!adminIds.isEmpty()) {
                pushHelper.pushAsync(adminIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                        .conversationId(convId)
                        .eventType("JOIN_REQUEST_REVIEWED")
                        .title(conv.getTitle())
                        .operatorNickname(operatorNick)
                        .build()));
            }
            return null;
        }

        List<Long> currentIds = listMemberIds(convId);
        if (!currentIds.contains(req.getInviteeId())) {
            addMember(convId, req.getInviteeId(), 1);
            redisTemplate.delete(RedisKeys.conversationMembers(convId));
        }

        List<Long> notifyIds = new ArrayList<>(currentIds);
        if (!notifyIds.contains(req.getInviteeId())) {
            notifyIds.add(req.getInviteeId());
        }
        pushHelper.pushAsync(notifyIds, WsPacket.of(WsPacketType.CONV_UPDATE, ConvUpdatePayload.builder()
                .conversationId(convId)
                .eventType("MEMBERS_CHANGED")
                .title(conv.getTitle())
                .operatorNickname(operatorNick)
                .build()));

        String inviterNick = userService.getNicknameCached(req.getInviterId());
        String inviteeNick = userService.getNicknameCached(req.getInviteeId());
        return operatorNick + " 同意了 " + inviterNick + " 邀请 " + inviteeNick + " 加入群聊";
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
        int opRole = operator.getRole() == null ? 1 : operator.getRole();
        int targetRole = target.getRole() == null ? 1 : target.getRole();
        if (!selfLeave) {
            if (opRole < 2) {
                throw new BizException(ErrorCode.FORBIDDEN, "仅群主或群管理员可移出其他成员");
            }
            if (targetRole >= opRole) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权移出群主或同级管理员");
            }
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

    private ImConversationMember requireGroupAdminOrOwner(Long convId, Long userId, String errorMsg) {
        ImConversationMember member = memberMapper.selectOne(new LambdaQueryWrapper<ImConversationMember>()
                .eq(ImConversationMember::getConversationId, convId)
                .eq(ImConversationMember::getUserId, userId));
        if (member == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该群成员");
        }
        if (member.getRole() == null || member.getRole() < 2) {
            throw new BizException(ErrorCode.FORBIDDEN, errorMsg);
        }
        return member;
    }

    private List<Long> listAdminAndOwnerIds(Long convId) {
        return memberMapper.selectList(new LambdaQueryWrapper<ImConversationMember>()
                        .eq(ImConversationMember::getConversationId, convId)
                        .ge(ImConversationMember::getRole, 2))
                .stream()
                .map(ImConversationMember::getUserId)
                .toList();
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
        member.setGroupRemark("");
        member.setGroupNickname("");
        member.setShowMemberNickname(1);
        memberMapper.insert(member);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle) {
        return toVO(conv, displayTitle, "", "", "", true, 0,
                0L, 0L, null, false, false, 2, null, null, null, null, null);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle, String groupRemark,
                                String announcementPublisher, String myGroupNickname,
                                boolean showMemberNickname, int pendingJoinRequestCount,
                                long lastReadSeq, long unreadCount, Long mentionSeq, boolean pinned, boolean muted,
                                int memberCount, Long peerUserId, String peerAvatar, Boolean peerOnline,
                                String peerStatusEmoji, String peerStatusText) {
        Long lastTime = conv.getLastMsgTime() != null
                ? conv.getLastMsgTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        Long announcementTime = conv.getAnnouncementUpdatedAt() != null
                ? conv.getAnnouncementUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        return ConversationVO.builder()
                .id(conv.getId())
                .type(conv.getType())
                .title(displayTitle)
                .groupRemark(groupRemark)
                .announcement(conv.getAnnouncement() != null ? conv.getAnnouncement() : "")
                .announcementUpdatedAt(announcementTime)
                .announcementPublisher(announcementPublisher)
                .joinApprovalRequired(Integer.valueOf(1).equals(conv.getJoinApprovalRequired()))
                .myGroupNickname(myGroupNickname)
                .showMemberNickname(showMemberNickname)
                .pendingJoinRequestCount(pendingJoinRequestCount)
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
