package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.mapper.ImGroupReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 群日程提醒延迟队列调度器：
 * 1. 高频热路径（每 2 秒）：Redis Lua 原子 ZRANGEBYSCORE + ZREM 弹出到期提醒，多节点部署互不冲突；
 * 2. 数据库兜底（每 30 秒）：扫描 DB 到期 PENDING 记录，防止 Redis 重启丢失 ZSet 数据；
 * 3. 双重幂等：触发前对 im_group_reminder 执行 CAS 更新 (WHERE status='PENDING')，保证全集群仅推送一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderSchedulerService {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> POP_DUE_REMINDERS_SCRIPT = new DefaultRedisScript<>(
            """
            local items = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            if #items > 0 then
                redis.call('ZREM', KEYS[1], unpack(items))
            end
            return items
            """,
            List.class
    );

    private final StringRedisTemplate redisTemplate;
    private final ImGroupReminderMapper reminderMapper;
    private final MessageService messageService;

    @Scheduled(fixedDelay = 2000L)
    public void pollRedisDelayQueue() {
        try {
            List<?> rawIds = redisTemplate.execute(
                    POP_DUE_REMINDERS_SCRIPT,
                    List.of(RedisKeys.reminderDelayZSet()),
                    String.valueOf(System.currentTimeMillis()),
                    "20"
            );
            if (rawIds == null || rawIds.isEmpty()) {
                return;
            }
            for (Object raw : rawIds) {
                try {
                    triggerReminderIfPending(Long.valueOf(String.valueOf(raw)));
                } catch (Exception e) {
                    log.error("处理到期提醒失败: rawId={}", raw, e);
                }
            }
        } catch (Exception e) {
            log.debug("轮询延迟队列跳过本次异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 30000L)
    public void compensateDueRemindersFromDb() {
        try {
            List<ImGroupReminder> dueList = reminderMapper.selectDuePending(20);
            for (ImGroupReminder r : dueList) {
                triggerReminderIfPending(r.getId());
            }
        } catch (Exception e) {
            log.debug("数据库提醒兜底扫描跳过本次异常: {}", e.getMessage());
        }
    }

    public void triggerReminderIfPending(Long reminderId) {
        int claimed = reminderMapper.markSentIfPending(reminderId);
        if (claimed == 0) {
            return;
        }
        ImGroupReminder reminder = reminderMapper.selectById(reminderId);
        if (reminder == null) {
            return;
        }
        log.info("延迟队列群提醒到点触发: reminderId={}, convId={}, title={}",
                reminder.getId(), reminder.getConversationId(), reminder.getTitle());
        messageService.publishScheduledReminder(reminder);
    }
}
