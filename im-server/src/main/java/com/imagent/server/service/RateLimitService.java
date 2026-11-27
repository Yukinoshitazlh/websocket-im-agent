package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.RateLimitProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 差异化限流服务（基于 Redis + Lua 原子令牌桶算法 + Agent 日配额控制）
 * - 普通消息：允许短时突发、平稳高频发送，保障正常群聊流畅体验；
 * - @AI 触发：独立低频令牌桶 + 每日调用次数上限，防止恶意刷屏导致大模型费用失控。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Redis + Lua 原子令牌桶脚本（O(1) 空间复杂度，单次 RTT 原子计算补水与扣减）
     * KEYS[1] = bucketKey
     * ARGV[1] = capacity, ARGV[2] = refillPerSecond, ARGV[3] = nowMs, ARGV[4] = requested, ARGV[5] = ttlSec
     */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>(
            """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl_sec = tonumber(ARGV[5])

            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local last_ts = tonumber(data[2])
            if tokens == nil then
                tokens = capacity
                last_ts = now_ms
            else
                local elapsed = math.max(0, now_ms - last_ts) / 1000.0
                tokens = math.min(capacity, tokens + elapsed * refill_rate)
                last_ts = now_ms
            end

            if tokens < requested then
                redis.call('HMSET', key, 'tokens', tokens, 'ts', last_ts)
                redis.call('EXPIRE', key, ttl_sec)
                return 0
            end

            tokens = tokens - requested
            redis.call('HMSET', key, 'tokens', tokens, 'ts', last_ts)
            redis.call('EXPIRE', key, ttl_sec)
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;

    public void checkChatSendAllowed(Long userId) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean allowed = tryConsumeToken(
                RedisKeys.chatRateLimit(userId),
                properties.getChatBucketCapacity(),
                properties.getChatRefillPerSecond(),
                120
        );
        if (!allowed) {
            log.warn("触发普通消息发送限流: userId={}", userId);
            throw new BizException(ErrorCode.RATE_LIMITED, "发送消息过于频繁，请稍后再试");
        }
    }

    public void checkAgentTriggerAllowed(Long userId) {
        checkAgentTriggerAllowed(userId, false);
    }

    /**
     * AI 触发限流与成本熔断：
     * - 令牌桶防短时刷屏对所有用户生效；
     * - 未启用 BYOK 时同时受“单用户日配额”和“全站每日公共总熔断上限”约束；
     * - 启用用户自带 API Key（BYOK）后跳过全站公共日配额扣减。
     */
    public void checkAgentTriggerAllowed(Long userId, boolean usingByok) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean burstAllowed = tryConsumeToken(
                RedisKeys.agentRateLimit(userId),
                properties.getAgentBucketCapacity(),
                properties.getAgentRefillPerSecond(),
                300
        );
        if (!burstAllowed) {
            log.warn("触发 @AI 频率限流: userId={}", userId);
            throw new BizException(ErrorCode.RATE_LIMITED, "@AI 调用过于频繁，请稍候几秒再试");
        }

        if (usingByok) {
            return;
        }

        String day = LocalDate.now().format(DAY_FMT);
        String dailyKey = RedisKeys.agentDailyQuota(userId, day);
        Long used = redisTemplate.opsForValue().increment(dailyKey);
        if (used != null && used == 1L) {
            redisTemplate.expire(dailyKey, Duration.ofHours(25));
        }
        if (used != null && used > properties.getAgentDailyQuota()) {
            log.warn("触发 @AI 每日配额上限: userId={}, used={}", userId, used);
            throw new BizException(ErrorCode.AGENT_QUOTA_EXCEEDED,
                    "今日公共 @AI 额度已达上限（" + properties.getAgentDailyQuota() + " 次），可在个人设置中配置自己的 API Key 继续使用");
        }

        if (properties.getAgentGlobalDailyQuota() > 0) {
            String globalKey = RedisKeys.agentGlobalDailyQuota(day);
            Long globalUsed = redisTemplate.opsForValue().increment(globalKey);
            if (globalUsed != null && globalUsed == 1L) {
                redisTemplate.expire(globalKey, Duration.ofHours(25));
            }
            if (globalUsed != null && globalUsed > properties.getAgentGlobalDailyQuota()) {
                log.warn("触发全站公共 @AI 成本熔断: day={}, globalUsed={}", day, globalUsed);
                throw new BizException(ErrorCode.AGENT_QUOTA_EXCEEDED,
                        "今日全站公共 AI 额度已触发成本保护熔断，请在个人设置中配置你自己的 API Key（BYOK）继续使用");
            }
        }
    }

    private boolean tryConsumeToken(String key, int capacity, double refillPerSec, int ttlSec) {
        Long result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillPerSec),
                String.valueOf(System.currentTimeMillis()),
                "1",
                String.valueOf(ttlSec)
        );
        return Long.valueOf(1L).equals(result);
    }
}
