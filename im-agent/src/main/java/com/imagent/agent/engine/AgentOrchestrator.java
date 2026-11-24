package com.imagent.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.config.AgentProperties;
import com.imagent.agent.entity.AgentApprovalEntity;
import com.imagent.agent.entity.AgentTaskEntity;
import com.imagent.agent.mapper.AgentApprovalMapper;
import com.imagent.agent.mapper.AgentTaskMapper;
import com.imagent.agent.tool.CreateGroupReminderTool;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.Result;
import com.imagent.common.mq.AgentControlEvent;
import com.imagent.common.mq.AgentTaskEvent;
import com.imagent.common.protocol.payload.AgentApprovalCardPayload;
import com.imagent.common.protocol.payload.AgentStreamPayload;
import com.imagent.common.protocol.payload.ServerAckPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 手写轻量 ReAct 调度引擎：
 * - 有界步数循环（max_steps 防工具调用死循环）
 * - Redis 短期滑动窗口记忆 + 流式增量缓冲
 * - 实时打断（CANCEL）截断落库
 * - 写工具 Human-in-the-Loop 状态机挂起（WAITING_APPROVAL）与唤醒恢复
 * - TTFT 首字延迟、总耗时、Token 消耗全链路记录
 */
@Slf4j
@Service
public class AgentOrchestrator {

    private static final String SYSTEM_PROMPT = """
            你是内置在即时聊天系统中的智能协作助手（AI 助手）。
            你可以调用 search_chat_history 检索当前会话的历史消息用于总结或查结论，
            也可以调用 create_group_reminder 为当前会话创建日程/会议提醒（属于写操作，需成员审批确认）。
            请用简洁、准确的中文回复。
            """;

    private static final int MAX_CONTEXT_TURNS = 6;
    private static final Duration CONTEXT_TTL = Duration.ofHours(24);
    private static final Duration STREAM_BUFFER_TTL = Duration.ofMinutes(30);

    private final AgentProperties properties;
    private final AgentTaskMapper taskMapper;
    private final AgentApprovalMapper approvalMapper;
    private final AgentPushService pushService;
    private final ActiveTaskRegistry activeTaskRegistry;
    private final LlmStreamClient llmStreamClient;
    private final WebClient imServerWebClient;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, AgentTool> toolMap;
    private final List<AgentTool> toolList;

    public AgentOrchestrator(AgentProperties properties,
                             AgentTaskMapper taskMapper,
                             AgentApprovalMapper approvalMapper,
                             AgentPushService pushService,
                             ActiveTaskRegistry activeTaskRegistry,
                             LlmStreamClient llmStreamClient,
                             @Qualifier("imServerWebClient") WebClient imServerWebClient,
                             StringRedisTemplate redisTemplate,
                             ObjectMapper objectMapper,
                             List<AgentTool> tools) {
        this.properties = properties;
        this.taskMapper = taskMapper;
        this.approvalMapper = approvalMapper;
        this.pushService = pushService;
        this.activeTaskRegistry = activeTaskRegistry;
        this.llmStreamClient = llmStreamClient;
        this.imServerWebClient = imServerWebClient;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.toolList = tools;
        this.toolMap = tools.stream().collect(Collectors.toMap(AgentTool::name, Function.identity()));
    }

    public void executeTask(AgentTaskEvent event) {
        Long taskId = event.getTaskId();
        Long convId = event.getConversationId();
        long taskStartMs = System.currentTimeMillis();
        activeTaskRegistry.register(taskId);

        try {
            updateTaskStatus(taskId, "RUNNING", 0, null);
            pushService.pushStream(convId, AgentStreamPayload.builder()
                    .taskId(taskId).conversationId(convId).stage("thinking").build());

            List<Map<String, Object>> messages = buildInitialMessages(convId, event.getUserPrompt());
            runReactLoop(taskId, convId, event.getTriggerUserId(), event.getTriggerMsgId(),
                    messages, 0, taskStartMs, event.getUserPrompt());
        } catch (Exception e) {
            log.error("Agent 任务执行失败: taskId={}", taskId, e);
            markTaskFailed(taskId, convId, e.getMessage(), taskStartMs);
        } finally {
            activeTaskRegistry.unregister(taskId);
        }
    }

    public void resumeAfterApproval(AgentControlEvent ctrl) {
        Long taskId = ctrl.getTaskId();
        AgentTaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !"WAITING_APPROVAL".equals(task.getStatus())) {
            log.info("忽略非挂起状态的审批唤醒事件: taskId={}, currentStatus={}",
                    taskId, task == null ? "NULL" : task.getStatus());
            return;
        }

        Long convId = task.getConversationId();
        long resumeStartMs = System.currentTimeMillis();
        activeTaskRegistry.register(taskId);

        try {
            AgentCheckpoint cp = objectMapper.readValue(task.getCheckpointState(), AgentCheckpoint.class);

            if ("RESUME_REJECTED".equals(ctrl.getControlType())) {
                String rejectMsg = "审批已拒绝，已取消执行工具 `" + cp.getPendingToolName() + "`。";
                pushService.pushStream(convId, AgentStreamPayload.builder()
                        .taskId(taskId).conversationId(convId).stage("delta").delta(rejectMsg).build());
                finalizeAndPersist(taskId, convId, cp.getTriggerMsgId(), rejectMsg, false, 0,
                        (int) (System.currentTimeMillis() - resumeStartMs), 0, 0, null);
                return;
            }

            AgentTool tool = toolMap.get(cp.getPendingToolName());
            if (tool == null) {
                throw new IllegalStateException("找不到待恢复的工具: " + cp.getPendingToolName());
            }

            updateTaskStatus(taskId, "RUNNING", cp.getCurrentStep(), null);
            pushService.pushStream(convId, AgentStreamPayload.builder()
                    .taskId(taskId).conversationId(convId).stage("tool_call").toolName(tool.name()).build());

            AgentToolContext toolCtx = AgentToolContext.builder()
                    .taskId(taskId)
                    .conversationId(convId)
                    .triggerUserId(cp.getTriggerUserId())
                    .build();
            String observation = tool.execute(toolCtx, cp.getPendingToolArgsJson());

            List<Map<String, Object>> messages = new ArrayList<>(cp.getMessages());
            appendToolTurn(messages, cp.getPendingToolCallId(), tool.name(), cp.getPendingToolArgsJson(), observation);

            runReactLoop(taskId, convId, cp.getTriggerUserId(), cp.getTriggerMsgId(),
                    messages, cp.getCurrentStep(), resumeStartMs, null);
        } catch (Exception e) {
            log.error("审批唤醒恢复执行失败: taskId={}", taskId, e);
            markTaskFailed(taskId, convId, e.getMessage(), resumeStartMs);
        } finally {
            activeTaskRegistry.unregister(taskId);
        }
    }

    private void runReactLoop(Long taskId,
                              Long convId,
                              Long triggerUserId,
                              Long triggerMsgId,
                              List<Map<String, Object>> messages,
                              int startStep,
                              long startMs,
                              String originalPrompt) throws Exception {
        Integer firstTtftMs = null;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        StringBuilder streamAccumulator = new StringBuilder();
        String streamBufKey = RedisKeys.agentStreamBuffer(taskId);

        for (int step = startStep + 1; step <= properties.getMaxSteps(); step++) {
            if (activeTaskRegistry.isCancelled(taskId)) {
                String partial = streamAccumulator.isEmpty() ? "（生成已由用户主动中止）" : streamAccumulator + "\n\n[已中止]";
                finalizeAndPersist(taskId, convId, triggerMsgId, partial, true, firstTtftMs,
                        (int) (System.currentTimeMillis() - startMs), totalPromptTokens, totalCompletionTokens, null);
                return;
            }

            updateTaskStatus(taskId, "RUNNING", step, null);

            LlmTurnResult turn = llmStreamClient.streamTurn(taskId, messages, toolList, delta -> {
                streamAccumulator.append(delta);
                redisTemplate.opsForValue().append(streamBufKey, delta);
                redisTemplate.expire(streamBufKey, STREAM_BUFFER_TTL);
                pushService.pushStream(convId, AgentStreamPayload.builder()
                        .taskId(taskId).conversationId(convId).stage("delta").delta(delta).build());
            });

            if (firstTtftMs == null && turn.getTtftMs() != null) {
                firstTtftMs = turn.getTtftMs();
            }
            totalPromptTokens += turn.getPromptTokens();
            totalCompletionTokens += turn.getCompletionTokens();

            if (turn.isAborted() || activeTaskRegistry.isCancelled(taskId)) {
                String partial = streamAccumulator.isEmpty() ? "（生成已由用户主动中止）" : streamAccumulator + "\n\n[已中止]";
                finalizeAndPersist(taskId, convId, triggerMsgId, partial, true, firstTtftMs,
                        (int) (System.currentTimeMillis() - startMs), totalPromptTokens, totalCompletionTokens, null);
                return;
            }

            if (turn.hasToolCall()) {
                AgentTool tool = toolMap.get(turn.getToolCallName());
                if (tool == null) {
                    appendToolTurn(messages, turn.getToolCallId(), turn.getToolCallName(),
                            turn.getToolCallArgumentsJson(), "错误：未知工具 " + turn.getToolCallName());
                    continue;
                }

                // 写操作工具：挂起状态机，持久化 Checkpoint，推送人机审批卡片后释放 Worker 线程
                if (tool.requiresApproval()) {
                    suspendForApproval(taskId, convId, triggerUserId, triggerMsgId, step, messages, tool, turn);
                    return;
                }

                // 读操作工具：直接执行并将 Observation 喂回下一轮 ReAct
                pushService.pushStream(convId, AgentStreamPayload.builder()
                        .taskId(taskId).conversationId(convId).stage("tool_call").toolName(tool.name()).build());
                AgentToolContext toolCtx = AgentToolContext.builder()
                        .taskId(taskId).conversationId(convId).triggerUserId(triggerUserId).build();
                String observation = tool.execute(toolCtx, turn.getToolCallArgumentsJson());
                appendToolTurn(messages, turn.getToolCallId(), tool.name(), turn.getToolCallArgumentsJson(), observation);
                continue;
            }

            // 拿到最终回复，落库并推送 done
            String finalAnswer = streamAccumulator.isEmpty() ? turn.getText() : streamAccumulator.toString();
            if (finalAnswer == null || finalAnswer.isBlank()) {
                finalAnswer = "我暂时没有生成有效内容，请换一种说法再试试。";
            }
            finalizeAndPersist(taskId, convId, triggerMsgId, finalAnswer, false, firstTtftMs,
                    (int) (System.currentTimeMillis() - startMs), totalPromptTokens, totalCompletionTokens, originalPrompt);
            return;
        }

        // 达到 max_steps 熔断保护，防止工具调用死循环
        String fallback = "已达到最大推理步数上限（" + properties.getMaxSteps() + " 步），任务已安全终止。";
        finalizeAndPersist(taskId, convId, triggerMsgId, fallback, false, firstTtftMs,
                (int) (System.currentTimeMillis() - startMs), totalPromptTokens, totalCompletionTokens, null);
    }

    private void suspendForApproval(Long taskId,
                                    Long convId,
                                    Long triggerUserId,
                                    Long triggerMsgId,
                                    int step,
                                    List<Map<String, Object>> messages,
                                    AgentTool tool,
                                    LlmTurnResult turn) throws Exception {
        LocalDateTime expireAt = LocalDateTime.now().plusMinutes(5);
        String summary = (tool instanceof CreateGroupReminderTool reminderTool)
                ? reminderTool.buildApprovalSummary(turn.getToolCallArgumentsJson())
                : ("执行写操作工具：" + tool.name());

        AgentApprovalEntity approval = new AgentApprovalEntity();
        approval.setTaskId(taskId);
        approval.setConversationId(convId);
        approval.setRequesterId(triggerUserId);
        approval.setToolName(tool.name());
        approval.setToolArgs(turn.getToolCallArgumentsJson() == null || turn.getToolCallArgumentsJson().isBlank()
                ? "{}" : turn.getToolCallArgumentsJson());
        approval.setSummary(summary);
        approval.setDecision("PENDING");
        approval.setExpireAt(expireAt);
        approvalMapper.insert(approval);

        AgentCheckpoint checkpoint = AgentCheckpoint.builder()
                .taskId(taskId)
                .conversationId(convId)
                .triggerUserId(triggerUserId)
                .triggerMsgId(triggerMsgId)
                .approvalId(approval.getId())
                .currentStep(step)
                .pendingToolCallId(turn.getToolCallId())
                .pendingToolName(tool.name())
                .pendingToolArgsJson(approval.getToolArgs())
                .messages(messages)
                .build();

        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(taskId);
        update.setStatus("WAITING_APPROVAL");
        update.setCurrentStep(step);
        update.setCheckpointState(objectMapper.writeValueAsString(checkpoint));
        taskMapper.updateById(update);

        long expireEpochMs = expireAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        pushService.pushApprovalCard(convId, AgentApprovalCardPayload.builder()
                .taskId(taskId)
                .approvalId(approval.getId())
                .conversationId(convId)
                .actionName(tool.name())
                .summary(summary)
                .expireAt(expireEpochMs)
                .build());
        log.info("写操作触发人机协同审批挂起: taskId={}, approvalId={}, tool={}", taskId, approval.getId(), tool.name());
    }

    private void finalizeAndPersist(Long taskId,
                                    Long convId,
                                    Long triggerMsgId,
                                    String finalContent,
                                    boolean aborted,
                                    Integer ttftMs,
                                    int totalMs,
                                    int promptTokens,
                                    int completionTokens,
                                    String originalPrompt) {
        ServerAckPayload persisted = persistReplyViaServer(convId, taskId, triggerMsgId, finalContent);

        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(taskId);
        update.setStatus(aborted ? "ABORTED" : "DONE");
        update.setReplyMsgId(persisted != null ? persisted.getMsgId() : null);
        update.setTtftMs(ttftMs);
        update.setTotalMs(totalMs);
        update.setPromptTokens(promptTokens);
        update.setCompletionTokens(completionTokens);
        taskMapper.updateById(update);

        if (!aborted && originalPrompt != null && !originalPrompt.isBlank()) {
            appendShortTermMemory(convId, originalPrompt, finalContent);
        }

        pushService.pushStream(convId, AgentStreamPayload.builder()
                .taskId(taskId)
                .conversationId(convId)
                .stage(aborted ? "aborted" : "done")
                .msgId(persisted != null ? persisted.getMsgId() : null)
                .seq(persisted != null ? persisted.getSeq() : null)
                .build());
        log.info("Agent 任务结束: taskId={}, status={}, ttftMs={}, totalMs={}, seq={}",
                taskId, aborted ? "ABORTED" : "DONE", ttftMs, totalMs, persisted != null ? persisted.getSeq() : null);
    }

    private ServerAckPayload persistReplyViaServer(Long convId, Long taskId, Long triggerMsgId, String content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", convId);
        body.put("taskId", taskId);
        body.put("content", content);
        body.put("msgType", "AGENT_TEXT");
        if (triggerMsgId != null) {
            body.put("replyToMsgId", triggerMsgId);
        }

        Result<ServerAckPayload> resp = imServerWebClient.post()
                .uri("/internal/messages/agent-reply")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Result<ServerAckPayload>>() {})
                .block(Duration.ofSeconds(10));
        return resp != null ? resp.getData() : null;
    }

    private List<Map<String, Object>> buildInitialMessages(Long convId, String userPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        List<String> recent = redisTemplate.opsForList().range(RedisKeys.agentContext(convId), -MAX_CONTEXT_TURNS, -1);
        if (recent != null) {
            for (String item : recent) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = objectMapper.readValue(item, Map.class);
                    messages.add(msg);
                } catch (Exception ignored) {
                }
            }
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }

    private void appendShortTermMemory(Long convId, String userPrompt, String assistantReply) {
        String key = RedisKeys.agentContext(convId);
        try {
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(Map.of("role", "user", "content", userPrompt)));
            String trimmedReply = assistantReply.length() > 400 ? assistantReply.substring(0, 400) : assistantReply;
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(Map.of("role", "assistant", "content", trimmedReply)));
            redisTemplate.opsForList().trim(key, -MAX_CONTEXT_TURNS, -1);
            redisTemplate.expire(key, CONTEXT_TTL);
        } catch (Exception e) {
            log.warn("写入短期记忆失败: convId={}", convId, e);
        }
    }

    private void appendToolTurn(List<Map<String, Object>> messages,
                                String toolCallId,
                                String toolName,
                                String argsJson,
                                String observation) {
        String safeCallId = (toolCallId == null || toolCallId.isBlank()) ? "call_1" : toolCallId;
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", "");
        assistantMsg.put("tool_calls", List.of(Map.of(
                "id", safeCallId,
                "type", "function",
                "function", Map.of("name", toolName, "arguments", argsJson == null ? "{}" : argsJson)
        )));
        messages.add(assistantMsg);

        Map<String, Object> toolMsg = new LinkedHashMap<>();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", safeCallId);
        toolMsg.put("name", toolName);
        toolMsg.put("content", observation);
        messages.add(toolMsg);
    }

    private void updateTaskStatus(Long taskId, String status, int step, String errorMsg) {
        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(taskId);
        update.setStatus(status);
        update.setCurrentStep(step);
        if (errorMsg != null) {
            update.setErrorMsg(errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg);
        }
        taskMapper.updateById(update);
    }

    private void markTaskFailed(Long taskId, Long convId, String errMsg, long startMs) {
        AgentTaskEntity update = new AgentTaskEntity();
        update.setId(taskId);
        update.setStatus("FAILED");
        update.setTotalMs((int) (System.currentTimeMillis() - startMs));
        update.setErrorMsg(errMsg != null && errMsg.length() > 500 ? errMsg.substring(0, 500) : errMsg);
        taskMapper.updateById(update);

        pushService.pushStream(convId, AgentStreamPayload.builder()
                .taskId(taskId)
                .conversationId(convId)
                .stage("error")
                .errorMessage("AI 助手处理失败：" + (errMsg == null ? "未知错误" : errMsg))
                .build());
    }
}
