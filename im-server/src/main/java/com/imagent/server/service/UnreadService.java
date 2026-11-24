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
 * 会话未读红点计数（Redis Hash）与已读水位（MySQL last_read_seq）管理
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
     * 新消息落库后，为除发送者外的会话成员递增未读计数
     */
    public void incrementUnreadForMembers(Long conversationId, Long senderId, List<Long> memberIds) {
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
     * 批量读取用户所有会话的未读红点数
     */
    public Map<Long, Long> getUserUnreadMap(Long userId) {
        Map<Object, Object> entries = redisTemplate.opsForHash().entries(RedisKeys.userUnreadHash(userId));
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

    /**
     * 推进用户已读水位并刷新/清零 Redis 未读计数
     */
    public void markAsRead(Long userId, Long conversationId, Long readSeq) {
        if (readSeq == null || readSeq < 0) {
            return;
        }
        memberMapper.advanceLastReadSeq(conversationId, userId, readSeq);

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
        log.debug("推进已读水位: userId={}, convId={}, readSeq={}, remainingUnread={}",
                userId, conversationId, readSeq, remaining);
    }
}
