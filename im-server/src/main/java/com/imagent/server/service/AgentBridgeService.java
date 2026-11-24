package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.mq.AgentControlEvent;
import com.imagent.common.mq.AgentTaskEvent;
import com.imagent.server.entity.ImAgentApproval;
import com.imagent.server.entity.ImAgentTask;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.mapper.ImAgentApprovalMapper;
import com.imagent.server.mapper.ImAgentTaskMapper;
import com.imagent.server.mapper.ImGroupReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * im-server 与 im-agent 之间的桥接层：
 * 1. 识别 @AI 消息并异步投递 AgentTaskEvent（不阻塞 IM 主链路）
 * 2. 受理客户端 AGENT_CANCEL / AGENT_APPROVAL 并通过 Fanout 交换机广播控制信号
 * 3. 提供任务观测与群提醒查询能力
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBridgeService {

    public static final Long AI_ASSISTANT_USER_ID = 10000L;
    private static final Duration CANCEL_FLAG_TTL = Duration.ofMinutes(10);

    private final ImAgentTaskMapper agentTaskMapper;
    private final ImAgentApprovalMapper agentApprovalMapper;
    private final ImGroupReminderMapper groupReminderMapper;
    private final ConversationService conversationService;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    public boolean shouldTriggerAgent(Boolean mentionFlag, String content) {
        if (Boolean.TRUE.equals(mentionFlag)) {
            return true;
        }
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return lower.contains("@ai") || content.contains("@AI助手");
    }

    /**
     * 创建 QUEUED 状态任务记录并异步投递 RabbitMQ，解耦 IM 主链路与长耗时推理
     */
    public Long enqueueAgentTask(Long conversationId, Long triggerUserId, Long triggerMsgId, String rawContent) {
        String cleanPrompt = rawContent
                .replace("@AI助手", "")
                .replace("@AI", "")
                .replace("@ai", "")
                .trim();
        if (cleanPrompt.isEmpty()) {
            cleanPrompt = "你好，请自我介绍一下你能帮我做什么。";
        }

        ImAgentTask task = new ImAgentTask();
        task.setConversationId(conversationId);
        task.setTriggerUserId(triggerUserId);
        task.setTriggerMsgId(triggerMsgId);
        task.setStatus("QUEUED");
        task.setCurrentStep(0);
        task.setPromptTokens(0);
        task.setCompletionTokens(0);
        agentTaskMapper.insert(task);

        AgentTaskEvent event = AgentTaskEvent.builder()
                .taskId(task.getId())
                .conversationId(conversationId)
                .triggerUserId(triggerUserId)
                .triggerMsgId(triggerMsgId)
                .userPrompt(cleanPrompt)
                .build();

        rabbitTemplate.convertAndSend(MqConstants.AGENT_EXCHANGE, MqConstants.AGENT_TASK_ROUTING_KEY, event);
        log.info("已异步投递 Agent 任务: taskId={}, convId={}, triggerUserId={}",
                task.getId(), conversationId, triggerUserId);
        return task.getId();
    }

    /**
     * 实时打断：写 Redis 取消标记（防任务尚在排队）+ 广播 Fanout 控制信号（瞬间命中运行中的 Worker）
     */
    public void requestCancel(Long userId, Long taskId, Long conversationId) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权中止该会话的 AI 任务");
        }
        redisTemplate.opsForValue().set(RedisKeys.agentCancelFlag(taskId), "1", CANCEL_FLAG_TTL);
        AgentControlEvent ctrl = AgentControlEvent.builder()
                .controlType("CANCEL")
                .taskId(taskId)
                .operatorUserId(userId)
                .build();
        rabbitTemplate.convertAndSend(MqConstants.AGENT_CTRL_EXCHANGE, "", ctrl);
        log.info("已广播 Agent 打断信号: taskId={}, operatorUserId={}", taskId, userId);
    }

    /**
     * 人机协同审批回调：数据库乐观 CAS 抢占审批单，防止重复点击导致写工具二次执行
     */
    public void handleApprovalDecision(Long userId, Long taskId, Long approvalId, boolean approved, String comment) {
        ImAgentApproval approval = agentApprovalMapper.selectById(approvalId);
        if (approval == null || !taskId.equals(approval.getTaskId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "审批单不存在");
        }
        if (!conversationService.isMember(approval.getConversationId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员，无权审批");
        }

        String decision = approved ? "APPROVED" : "REJECTED";
        int updated = agentApprovalMapper.decideIfPending(
                approvalId, taskId, userId, decision, comment == null ? "" : comment);
        if (updated == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "该审批单已处理或已过期，请勿重复操作");
        }

        AgentControlEvent ctrl = AgentControlEvent.builder()
                .controlType(approved ? "RESUME_APPROVED" : "RESUME_REJECTED")
                .taskId(taskId)
                .approvalId(approvalId)
                .operatorUserId(userId)
                .comment(comment)
                .build();
        rabbitTemplate.convertAndSend(MqConstants.AGENT_CTRL_EXCHANGE, "", ctrl);
        log.info("审批决策生效并广播唤醒信号: taskId={}, approvalId={}, decision={}, approver={}",
                taskId, approvalId, decision, userId);
    }

    public List<ImAgentTask> listRecentTasks(Long userId, Long conversationId) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该会话任务");
        }
        return agentTaskMapper.selectList(new LambdaQueryWrapper<ImAgentTask>()
                .eq(ImAgentTask::getConversationId, conversationId)
                .orderByDesc(ImAgentTask::getCreatedAt)
                .last("LIMIT 20"));
    }

    public List<ImGroupReminder> listGroupReminders(Long userId, Long conversationId) {
        if (!conversationService.isMember(conversationId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看该会话提醒");
        }
        return groupReminderMapper.selectList(new LambdaQueryWrapper<ImGroupReminder>()
                .eq(ImGroupReminder::getConversationId, conversationId)
                .orderByDesc(ImGroupReminder::getCreatedAt)
                .last("LIMIT 20"));
    }
}
