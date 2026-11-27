package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.server.entity.ImConversation;
import com.imagent.server.mapper.ImConversationMapper;
import com.imagent.server.mapper.ImConversationMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话未读红点计数（Redis Hash）、已读水位（MySQL last_read_seq + Redis Hash）与被 @ 强提醒管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnreadService {

    private static final Duration UNREAD_TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;
    private final ImConversationMemberMapper memberMapper;
    private final ImConversationMapper conversationMapper;

    /**
     * 新消息落库后，自动恢复被隐藏的会话并为除发送者外的会话成员递增未读计数
     */
    public void incrementUnreadForMembers(Long conversationId, Long senderId, List<Long> memberIds) {
        memberMapper.unhideAllMembers(conversationId);
        String convField = String.valueOf(conversationId);
        for (Long memberId : memberIds) {
            if (memberId.equals(senderId)) {
                continue;
            }
            String key = RedisKeys.userUnreadHash(memberId);
            redisTemplate.opsForHash().increment(key, convField, 1L);
            redisTemplate.expire(key, UNREAD_TTL);
        }
    }

    /**
     * 记录被 @ 成员在该会话内最早一条未读 @ 消息的 seq（HSETNX 保留最早一条，点进会话可一键跳转）
     */
    public void markMentioned(Long conversationId, Long senderId, Long seq,
                              List<String> atUserIds, List<Long> memberIds) {
        if (atUserIds == null || atUserIds.isEmpty() || seq == null) {
            return;
        }
        String convField = String.valueOf(conversationId);
        String seqStr = String.valueOf(seq);
        boolean atAll = atUserIds.stream().anyMatch(v -> "ALL".equalsIgnoreCase(v));

        if (atAll) {
            for (Long memberId : memberIds) {
                if (!memberId.equals(senderId)) {
                    String key = RedisKeys.userMentionHash(memberId);
                    redisTemplate.opsForHash().putIfAbsent(key, convField, seqStr);
                    redisTemplate.expire(key, UNREAD_TTL);
                }
            }
            return;
        }

        for (String raw : atUserIds) {
            try {
                Long targetId = Long.valueOf(raw);
                if (!targetId.equals(senderId) && memberIds.contains(targetId)) {
                    String key = RedisKeys.userMentionHash(targetId);
                    redisTemplate.opsForHash().putIfAbsent(key, convField, seqStr);
                    redisTemplate.expire(key, UNREAD_TTL);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /**
     * 批量读取用户所有会话的未读红点数
     */
    public Map<Long, Long> getUserUnreadMap(Long userId) {
        return readLongHash(RedisKeys.userUnreadHash(userId));
    }

    /**
     * 批量读取用户所有会话中被 @ 的最早 seq
     */
    public Map<Long, Long> getUserMentionMap(Long userId) {
        return readLongHash(RedisKeys.userMentionHash(userId));
    }

    /**
     * 读取会话下所有成员的 Redis 已读水位缓存（用于零写放大计算已读/未读进度）
     */
    public Map<Long, Long> getConversationReadWatermarks(Long conversationId) {
        return readLongHash(RedisKeys.conversationReadSeqHash(conversationId));
    }

    /**
     * 推进用户已读水位并刷新/清零 Redis 未读计数与被 @ 标记；
     * 返回水位是否有实质前进，供上层决定是否广播 READ_RECEIPT 实时回执。
     */
    public boolean markAsRead(Long userId, Long conversationId, Long readSeq) {
        if (readSeq == null || readSeq < 0) {
            return false;
        }
        memberMapper.advanceLastReadSeq(conversationId, userId, readSeq);

        // 1. 维护会话级已读水位 Hash（单调前进，零写放大支撑已读进度圈）
        String readSeqKey = RedisKeys.conversationReadSeqHash(conversationId);
        String userField = String.valueOf(userId);
        Object prevObj = redisTemplate.opsForHash().get(readSeqKey, userField);
        long prevSeq = 0L;
        if (prevObj != null) {
            try {
                prevSeq = Long.parseLong(String.valueOf(prevObj));
            } catch (NumberFormatException ignored) {
            }
        }
        boolean advanced = readSeq > prevSeq;
        if (advanced || prevObj == null) {
            redisTemplate.opsForHash().put(readSeqKey, userField, String.valueOf(Math.max(prevSeq, readSeq)));
            redisTemplate.expire(readSeqKey, UNREAD_TTL);
        }

        // 2. 更新/清零个人未读数
        String key = RedisKeys.userUnreadHash(userId);
        String convField = String.valueOf(conversationId);
        ImConversation conv = conversationMapper.selectById(conversationId);
        long currentSeq = (conv != null && conv.getCurrentSeq() != null) ? conv.getCurrentSeq() : readSeq;
        long remaining = Math.max(0L, currentSeq - readSeq);
        if (remaining == 0L) {
            redisTemplate.opsForHash().delete(key, convField);
        } else {
            redisTemplate.opsForHash().put(key, convField, String.valueOf(remaining));
            redisTemplate.expire(key, UNREAD_TTL);
        }

        // 3. 若已读水位跨过最早的 @ 提醒消息，则清零 [有人@我] 标记
        String mentionKey = RedisKeys.userMentionHash(userId);
        Object atObj = redisTemplate.opsForHash().get(mentionKey, convField);
        if (atObj != null) {
            try {
                long atSeq = Long.parseLong(String.valueOf(atObj));
                if (readSeq >= atSeq) {
                    redisTemplate.opsForHash().delete(mentionKey, convField);
                }
            } catch (NumberFormatException ignored) {
                redisTemplate.opsForHash().delete(mentionKey, convField);
            }
        }

        log.debug("推进已读水位: userId={}, convId={}, readSeq={}, advanced={}, remainingUnread={}",
                userId, conversationId, readSeq, advanced, remaining);
        return advanced;
    }

    /**
     * 发送者热路径快速清零自身未读缓存
     */
    public void clearSenderUnreadFast(Long senderId, Long conversationId) {
        redisTemplate.opsForHash().delete(RedisKeys.userUnreadHash(senderId), String.valueOf(conversationId));
        redisTemplate.opsForHash().delete(RedisKeys.userMentionHash(senderId), String.valueOf(conversationId));
    }

    /**
     * 手动标为未读时，保证 Redis 未读计数至少为 1
     */
    public void markManualUnreadCache(Long userId, Long conversationId) {
        String key = RedisKeys.userUnreadHash(userId);
        String convField = String.valueOf(conversationId);
        Object existing = redisTemplate.opsForHash().get(key, convField);
        long current = 0L;
        if (existing != null) {
            try {
                current = Long.parseLong(String.valueOf(existing));
            } catch (NumberFormatException ignored) {
            }
        }
        if (current < 1L) {
            redisTemplate.opsForHash().put(key, convField, "1");
            redisTemplate.expire(key, UNREAD_TTL);
        }
    }

    /**
     * 清理个人在指定会话的未读红点与 @ 提醒缓存（保留全群已读水位）
     */
    public void clearUserConvUnread(Long userId, Long conversationId) {
        String convField = String.valueOf(conversationId);
        redisTemplate.opsForHash().delete(RedisKeys.userUnreadHash(userId), convField);
        redisTemplate.opsForHash().delete(RedisKeys.userMentionHash(userId), convField);
    }

    /**
     * 成员退群/被踢时清理其水位与未读缓存
     */
    public void removeMemberState(Long conversationId, Long userId) {
        String convField = String.valueOf(conversationId);
        redisTemplate.opsForHash().delete(RedisKeys.conversationReadSeqHash(conversationId), String.valueOf(userId));
        redisTemplate.opsForHash().delete(RedisKeys.userUnreadHash(userId), convField);
        redisTemplate.opsForHash().delete(RedisKeys.userMentionHash(userId), convField);
    }

    private Map<Long, Long> readLongHash(String key) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
        if (entries.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> result = new HashMap<>(entries.size());
        entries.forEach((k, v) -> {
            try {
                result.put(Long.valueOf(String.valueOf(k)), Long.parseLong(String.valueOf(v)));
            } catch (NumberFormatException ignored) {
            }
        });
        return result;
    }
}
