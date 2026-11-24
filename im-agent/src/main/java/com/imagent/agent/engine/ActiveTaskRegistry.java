package com.imagent.agent.engine;

import com.imagent.common.constant.RedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 运行中 Agent 任务与流式连接注册表：
 * 结合内存 Disposable 实时断流 + Redis 取消标记，实现毫秒级中断生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActiveTaskRegistry {

    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Disposable> activeStreams = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;

    public void register(Long taskId) {
        cancelFlags.put(taskId, new AtomicBoolean(false));
    }

    public void attachStream(Long taskId, Disposable disposable) {
        if (isCancelled(taskId)) {
            disposable.dispose();
            return;
        }
        activeStreams.put(taskId, disposable);
    }

    public void cancel(Long taskId) {
        AtomicBoolean flag = cancelFlags.computeIfAbsent(taskId, k -> new AtomicBoolean(false));
        flag.set(true);
        Disposable stream = activeStreams.remove(taskId);
        if (stream != null && !stream.isDisposed()) {
            stream.dispose();
            log.info("已实时中断下游 LLM 流式连接: taskId={}", taskId);
        }
    }

    public boolean isCancelled(Long taskId) {
        AtomicBoolean flag = cancelFlags.get(taskId);
        if (flag != null && flag.get()) {
            return true;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(RedisKeys.agentCancelFlag(taskId)));
    }

    public void unregister(Long taskId) {
        cancelFlags.remove(taskId);
        Disposable stream = activeStreams.remove(taskId);
        if (stream != null && !stream.isDisposed()) {
            stream.dispose();
        }
    }
}
