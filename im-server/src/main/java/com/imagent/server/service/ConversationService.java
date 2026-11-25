package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
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
    private final UnreadService unreadService;
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
                        .in(ImConversation::getId, convIds)
                        .orderByDesc(ImConversation::getLastMsgTime)
                        .orderByDesc(ImConversation::getCreatedAt));

        List<Long> directConvIds = conversations.stream()
                .filter(c -> Integer.valueOf(1).equals(c.getType()))
                .map(ImConversation::getId)
                .toList();

        Map<Long, ImUser> directPeerMap = resolveDirectPeers(directConvIds, userId);
        Map<Long, Boolean> peerOnlineMap = resolveOnlineFlags(directPeerMap.values().stream().map(ImUser::getId).distinct().toList());
        Map<Long, Long> lastReadMap = memberships.stream()
                .collect(Collectors.toMap(
                        ImConversationMember::getConversationId,
                        m -> m.getLastReadSeq() == null ? 0L : m.getLastReadSeq(),
                        (a, b) -> Math.max(a, b)
                ));
        Map<Long, Long> redisUnreadMap = unreadService.getUserUnreadMap(userId);

        List<ConversationVO> result = new ArrayList<>(conversations.size());
        for (ImConversation conv : conversations) {
            boolean isDirect = Integer.valueOf(1).equals(conv.getType());
            ImUser peer = isDirect ? directPeerMap.get(conv.getId()) : null;
            String displayTitle = isDirect
                    ? (peer != null ? peer.getNickname() : "单聊会话")
                    : conv.getTitle();
            Long peerUserId = peer != null ? peer.getId() : null;
            Boolean peerOnline = peerUserId != null ? peerOnlineMap.getOrDefault(peerUserId, false) : null;
            long lastRead = lastReadMap.getOrDefault(conv.getId(), 0L);
            long currentSeq = conv.getCurrentSeq() == null ? 0L : conv.getCurrentSeq();
            long unread = redisUnreadMap.getOrDefault(conv.getId(), Math.max(0L, currentSeq - lastRead));
            result.add(toVO(conv, displayTitle, lastRead, unread, peerUserId, peerOnline));
        }
        return result;
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

        Map<Long, ImUser> result = new java.util.HashMap<>();
        for (ImConversationMember m : peerMembers) {
            ImUser u = userMap.get(m.getUserId());
            if (u != null) {
                result.putIfAbsent(m.getConversationId(), u);
            }
        }
        return result;
    }

    /**
     * 批量查询 Redis 在线路由表，单次 MGET 判定对方在线绿点状态
     */
    private Map<Long, Boolean> resolveOnlineFlags(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<String> keys = userIds.stream().map(RedisKeys::userRoute).toList();
        List<String> values = redisTemplate.opsForValue().multiGet(keys);
        Map<Long, Boolean> map = new java.util.HashMap<>(userIds.size());
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
        memberMapper.insert(member);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle) {
        return toVO(conv, displayTitle, 0L, 0L, null, null);
    }

    private ConversationVO toVO(ImConversation conv, String displayTitle, long lastReadSeq,
                                long unreadCount, Long peerUserId, Boolean peerOnline) {
        Long lastTime = conv.getLastMsgTime() != null
                ? conv.getLastMsgTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        return ConversationVO.builder()
                .id(conv.getId())
                .type(conv.getType())
                .title(displayTitle)
                .currentSeq(conv.getCurrentSeq())
                .lastReadSeq(lastReadSeq)
                .unreadCount(unreadCount)
                .peerUserId(peerUserId)
                .peerOnline(peerOnline)
                .lastMsgPreview(conv.getLastMsgPreview())
                .lastMsgTime(lastTime)
                .build();
    }
}
