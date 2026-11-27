package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.mq.AgentControlEvent;
import com.imagent.common.mq.AgentTaskEvent;
import com.imagent.server.dto.AgentMetricsVO;
import com.imagent.server.dto.AgentSummarizeRequest;
import com.imagent.server.entity.ImAgentApproval;
import com.imagent.server.entity.ImAgentTask;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.entity.ImMessage;
import com.imagent.server.entity.ImUser;
import com.imagent.server.mapper.ImAgentApprovalMapper;
import com.imagent.server.mapper.ImAgentTaskMapper;
import com.imagent.server.mapper.ImGroupReminderMapper;
import com.imagent.server.mapper.ImMessageMapper;
import com.imagent.server.mapper.ImUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * im-server 与 im-agent 之间的桥接层：
 * 1. 识别 @AI 消息并异步投递 AgentTaskEvent（不阻塞 IM 主链路）
 * 2. 支持飞书式「未读省流总结」与微信式「多选消息一键 AI 提炼」
 * 3. 受理客户端 AGENT_CANCEL / AGENT_APPROVAL 并通过 Fanout 交换机广播控制信号
 * 4. 提供任务观测与群提醒查询能力
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
    private final ImMessageMapper messageMapper;
    private final ImUserMapper userMapper;
    private final ConversationService conversationService;
    private final RateLimitService rateLimitService;
    private final UserLlmConfigService userLlmConfigService;
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
        return lower.contains("@ai") || content.contains("@AI助手") || content.contains("@AI 助手");
    }

    /**
     * 创建 QUEUED 状态任务记录并异步投递 RabbitMQ，解耦 IM 主链路与长耗时推理
     */
    public Long enqueueAgentTask(Long conversationId, Long triggerUserId, Long triggerMsgId, String rawContent) {
        String cleanPrompt = rawContent
                .replace("@AI 助手", "")
                .replace("@AI助手", "")
                .replace("@AI", "")
                .replace("@ai", "")
                .trim();
        if (cleanPrompt.isEmpty()) {
            cleanPrompt = "你好，请自我介绍一下你能帮我做什么。";
        }
        return submitTask(conversationId, triggerUserId, triggerMsgId, cleanPrompt);
    }

    public boolean isUserUsingByok(Long userId) {
        return userLlmConfigService.hasActiveByok(userId);
    }

    /**
     * 飞书「未读一键省流总结」与微信「多选消息 AI 提炼」统一异步任务入口
     */
    public Long enqueueSummaryTask(Long userId, AgentSummarizeRequest req) {
        Long convId = req.getConversationId();
        if (!conversationService.isMember(convId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "你不是该会话成员，无权触发 AI 总结");
        }
        rateLimitService.checkAgentTriggerAllowed(userId, userLlmConfigService.hasActiveByok(userId));

        String mode = req.getMode().trim().toUpperCase(Locale.ROOT);
        List<ImMessage> targetMessages;
        if ("SELECTED".equals(mode)) {
            if (req.getMsgIds() == null || req.getMsgIds().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "请至少勾选一条消息再交给 AI 提炼");
            }
            List<Long> safeIds = req.getMsgIds().stream().distinct().limit(40).toList();
            targetMessages = messageMapper.selectList(
                    new LambdaQueryWrapper<ImMessage>()
                            .eq(ImMessage::getConversationId, convId)
                            .in(ImMessage::getId, safeIds)
                            .eq(ImMessage::getStatus, 1)
                            .orderByAsc(ImMessage::getSeq));
        } else if ("UNREAD".equals(mode)) {
            long afterSeq = req.getAfterSeq() != null && req.getAfterSeq() >= 0 ? req.getAfterSeq() : 0L;
            targetMessages = messageMapper.selectList(
                    new LambdaQueryWrapper<ImMessage>()
                            .eq(ImMessage::getConversationId, convId)
                            .gt(ImMessage::getSeq, afterSeq)
                            .eq(ImMessage::getStatus, 1)
                            .ne(ImMessage::getMsgType, "SYSTEM")
                            .orderByAsc(ImMessage::getSeq)
                            .last("LIMIT 40"));
        } else {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的总结模式: " + mode);
        }

        if (targetMessages.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "范围内暂无可供总结的有效消息");
        }

        Set<Long> senderIds = targetMessages.stream().map(ImMessage::getSenderId).collect(Collectors.toSet());
        Map<Long, String> nickMap = senderIds.isEmpty()
                ? Collections.emptyMap()
                : userMapper.selectBatchIds(senderIds).stream()
                .collect(Collectors.toMap(ImUser::getId, ImUser::getNickname, (a, b) -> a));

        StringBuilder sb = new StringBuilder();
        sb.append("[SUMMARY_MODE:").append(mode).append("]\n");
        sb.append("请用简洁清晰的中文 Markdown 总结以下 ")
                .append(targetMessages.size())
                .append(" 条聊天记录，提炼【核心议题】【关键结论】与【待办事项】：\n");
        for (ImMessage m : targetMessages) {
            String nick = nickMap.getOrDefault(m.getSenderId(), "用户" + m.getSenderId());
            String content = formatMsgForSummary(m);
            sb.append("- ").append(nick).append(" (#").append(m.getSeq()).append("): ").append(content).append("\n");
        }

        Long triggerMsgId = targetMessages.get(targetMessages.size() - 1).getId();
        return submitTask(convId, userId, triggerMsgId, sb.toString());
    }

    private String formatMsgForSummary(ImMessage m) {
        if ("IMAGE".equals(m.getMsgType())) {
            return "[分享了一张图片]";
        }
        if ("FILE".equals(m.getMsgType())) {
            return "[分享了文件: " + m.getContent() + "]";
        }
        if ("FORWARD_BUNDLE".equals(m.getMsgType())) {
            return "[合并转发聊天记录: " + m.getContent() + "]";
        }
        String text = m.getContent() == null ? "" : m.getContent().replace('\n', ' ');
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private Long submitTask(Long conversationId, Long triggerUserId, Long triggerMsgId, String prompt) {
        ImAgentTask task = new ImAgentTask();
        task.setConversationId(conversationId);
        task.setTriggerUserId(triggerUserId);
        task.setTriggerMsgId(triggerMsgId);
        task.setStatus("QUEUED");
        task.setCurrentStep(0);
        task.setPromptTokens(0);
        task.setCompletionTokens(0);
        agentTaskMapper.insert(task);

        UserLlmConfigService.ResolvedLlmConfig byok = userLlmConfigService.resolveActiveConfig(triggerUserId);
        AgentTaskEvent event = AgentTaskEvent.builder()
                .taskId(task.getId())
                .conversationId(conversationId)
                .triggerUserId(triggerUserId)
                .triggerMsgId(triggerMsgId)
                .userPrompt(prompt)
                .customLlmBaseUrl(byok != null ? byok.baseUrl() : null)
                .customLlmApiKey(byok != null ? byok.apiKey() : null)
                .customLlmModel(byok != null ? byok.model() : null)
                .build();

        rabbitTemplate.convertAndSend(MqConstants.AGENT_EXCHANGE, MqConstants.AGENT_TASK_ROUTING_KEY, event);
        log.info("已异步投递 Agent 任务: taskId={}, convId={}, triggerUserId={}, byok={}",
                task.getId(), conversationId, triggerUserId, byok != null);
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

    public AgentMetricsVO getConversationMetrics(Long userId, Long conversationId) {
        List<ImAgentTask> tasks = listRecentTasks(userId, conversationId);
        List<ImGroupReminder> reminders = listGroupReminders(userId, conversationId);

        int done = 0, aborted = 0, waiting = 0, failed = 0;
        int ttftSum = 0, ttftCnt = 0;
        int totalMsSum = 0, totalMsCnt = 0;
        int promptTokens = 0, completionTokens = 0;

        for (ImAgentTask t : tasks) {
            String st = t.getStatus();
            if ("DONE".equals(st)) done++;
            else if ("ABORTED".equals(st)) aborted++;
            else if ("WAITING_APPROVAL".equals(st)) waiting++;
            else if ("FAILED".equals(st)) failed++;

            if (t.getTtftMs() != null) { ttftSum += t.getTtftMs(); ttftCnt++; }
            if (t.getTotalMs() != null) { totalMsSum += t.getTotalMs(); totalMsCnt++; }
            if (t.getPromptTokens() != null) promptTokens += t.getPromptTokens();
            if (t.getCompletionTokens() != null) completionTokens += t.getCompletionTokens();
        }

        return AgentMetricsVO.builder()
                .totalTasks(tasks.size())
                .doneCount(done)
                .abortedCount(aborted)
                .waitingApprovalCount(waiting)
                .failedCount(failed)
                .avgTtftMs(ttftCnt > 0 ? ttftSum / ttftCnt : null)
                .avgTotalMs(totalMsCnt > 0 ? totalMsSum / totalMsCnt : null)
                .totalPromptTokens(promptTokens)
                .totalCompletionTokens(completionTokens)
                .recentTasks(tasks)
                .reminders(reminders)
                .build();
    }
}
