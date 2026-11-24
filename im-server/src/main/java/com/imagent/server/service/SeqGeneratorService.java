package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.mapper.ImMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 会话级严格单调递增 seq 分配器
 * 1. 热路径：Lua 脚本原子判断 EXISTS + INCR（单次 RTT、无锁高性能）；
 * 2. 冷启动/Redis 重启恢复：基于分布式锁 (SETNX + TTL + Lua 安全释放) 从 MySQL MAX(seq) 重建基准，防止并发撞库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeqGeneratorService {

    private static final DefaultRedisScript<Long> INCR_IF_EXISTS_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCR', KEYS[1]) end return -1",
            Long.class
    );

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class
    );

    private static final Duration LOCK_TTL = Duration.ofSeconds(5);
    private static final int MAX_LOCK_RETRIES = 25;
    private static final long RETRY_SLEEP_MS = 20L;

    private final StringRedisTemplate redisTemplate;
    private final ImMessageMapper messageMapper;

    public Long nextSeq(Long conversationId) {
        String seqKey = RedisKeys.conversationSeq(conversationId);

        // 1. 热路径：key 已存在时单次原子自增
        Long fastSeq = redisTemplate.execute(INCR_IF_EXISTS_SCRIPT, List.of(seqKey));
        if (fastSeq != null && fastSeq > 0) {
            return fastSeq;
        }

        // 2. 冷启动路径：竞争分布式锁从 DB 恢复当前最大 seq
        String lockKey = RedisKeys.seqInitLock(conversationId);
        String lockToken = UUID.randomUUID().toString();
        for (int attempt = 0; attempt < MAX_LOCK_RETRIES; attempt++) {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, LOCK_TTL);
            if (Boolean.TRUE.equals(acquired)) {
                try {
                    // Double-check：防止等待期间已被其他线程初始化完成
                    Long doubleChecked = redisTemplate.execute(INCR_IF_EXISTS_SCRIPT, List.of(seqKey));
                    if (doubleChecked != null && doubleChecked > 0) {
                        return doubleChecked;
                    }
                    Long dbMaxSeq = messageMapper.selectMaxSeq(conversationId);
                    long nextVal = (dbMaxSeq == null ? 0L : dbMaxSeq) + 1L;
                    redisTemplate.opsForValue().set(seqKey, String.valueOf(nextVal));
                    log.info("冷启动恢复会话 seq 基准: convId={}, dbMaxSeq={}, allocatedSeq={}",
                            conversationId, dbMaxSeq, nextVal);
                    return nextVal;
                } finally {
                    redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey), lockToken);
                }
            }

            // 未抢到锁：短暂退避后先重试热路径
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.INTERNAL_ERROR, "分配消息序号被中断");
            }
            Long retrySeq = redisTemplate.execute(INCR_IF_EXISTS_SCRIPT, List.of(seqKey));
            if (retrySeq != null && retrySeq > 0) {
                return retrySeq;
            }
        }

        throw new BizException(ErrorCode.INTERNAL_ERROR, "会话序号初始化繁忙，请稍后重试");
    }
}
