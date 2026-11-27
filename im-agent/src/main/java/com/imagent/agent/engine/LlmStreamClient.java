package com.imagent.agent.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.config.AgentProperties;
import com.imagent.agent.tool.CreateGroupReminderTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 双模流式 LLM 客户端：
 * 1. 配置了 LLM_API_KEY：通过 WebClient 对接 OpenAI 兼容流式 SSE 接口（支持 DeepSeek / 通义千问 / OpenAI 等），
 *    完整支持原生 Function Calling、TTFT 首字延迟统计与 Disposable 毫秒级中断；
 * 2. 未配置 LLM_API_KEY：自动启用本地仿真流式模式，仍会真实触发群聊检索工具、人机审批挂起/恢复与流式打断。
 */
@Slf4j
@Component
public class LlmStreamClient {

    private static final DateTimeFormatter REMIND_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebClient llmWebClient;
    private final AgentProperties properties;
    private final ActiveTaskRegistry activeTaskRegistry;
    private final ObjectMapper objectMapper;

    public LlmStreamClient(@Qualifier("llmWebClient") WebClient llmWebClient,
                           AgentProperties properties,
                           ActiveTaskRegistry activeTaskRegistry,
                           ObjectMapper objectMapper) {
        this.llmWebClient = llmWebClient;
        this.properties = properties;
        this.activeTaskRegistry = activeTaskRegistry;
        this.objectMapper = objectMapper;
    }

    /** 可选的每任务自定义大模型配置（用户 BYOK） */
    public record LlmOverride(String baseUrl, String apiKey, String model) {
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public LlmTurnResult streamTurn(Long taskId,
                                    List<Map<String, Object>> messages,
                                    List<AgentTool> tools,
                                    Consumer<String> onDelta) {
        return streamTurn(taskId, messages, tools, null, onDelta);
    }

    public LlmTurnResult streamTurn(Long taskId,
                                    List<Map<String, Object>> messages,
                                    List<AgentTool> tools,
                                    LlmOverride override,
                                    Consumer<String> onDelta) {
        String effectiveApiKey = (override != null && override.hasApiKey())
                ? override.apiKey().trim()
                : properties.getLlmApiKey();
        if (effectiveApiKey == null || effectiveApiKey.isBlank()) {
            return simulateStreamTurn(taskId, messages, onDelta);
        }
        String effectiveBaseUrl = (override != null && override.baseUrl() != null && !override.baseUrl().isBlank())
                ? override.baseUrl().trim()
                : properties.getLlmBaseUrl();
        String effectiveModel = (override != null && override.model() != null && !override.model().isBlank())
                ? override.model().trim()
                : properties.getLlmModel();
        return callOpenAiCompatibleStream(taskId, messages, tools, effectiveBaseUrl, effectiveApiKey, effectiveModel, onDelta);
    }

    private LlmTurnResult callOpenAiCompatibleStream(Long taskId,
                                                     List<Map<String, Object>> messages,
                                                     List<AgentTool> tools,
                                                     String baseUrl,
                                                     String apiKey,
                                                     String model,
                                                     Consumer<String> onDelta) {
        long startNs = System.nanoTime();
        StringBuilder textBuf = new StringBuilder();
        StringBuilder toolArgsBuf = new StringBuilder();
        AtomicReference<String> toolCallIdRef = new AtomicReference<>();
        AtomicReference<String> toolNameRef = new AtomicReference<>();
        AtomicReference<Integer> ttftRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        CountDownLatch doneLatch = new CountDownLatch(1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", true);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", buildOpenAiToolsSpec(tools));
        }

        String endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";

        Disposable disposable = llmWebClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(properties.getStepTimeoutSeconds()))
                .subscribe(
                        sse -> {
                            String data = sse.data();
                            if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) {
                                return;
                            }
                            if (activeTaskRegistry.isCancelled(taskId)) {
                                activeTaskRegistry.cancel(taskId);
                                doneLatch.countDown();
                                return;
                            }
                            parseSseChunk(data, startNs, ttftRef, textBuf, toolCallIdRef, toolNameRef, toolArgsBuf, onDelta);
                        },
                        err -> {
                            errorRef.set(err);
                            doneLatch.countDown();
                        },
                        doneLatch::countDown
                );

        activeTaskRegistry.attachStream(taskId, disposable);

        try {
            boolean finished = doneLatch.await(properties.getStepTimeoutSeconds() + 5L, TimeUnit.SECONDS);
            if (!finished) {
                disposable.dispose();
                throw new RuntimeException("大模型流式响应超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disposable.dispose();
        }

        boolean aborted = activeTaskRegistry.isCancelled(taskId);
        if (!aborted && errorRef.get() != null) {
            throw new RuntimeException("调用大模型接口失败: " + errorRef.get().getMessage(), errorRef.get());
        }

        String finalText = textBuf.toString();
        return LlmTurnResult.builder()
                .text(finalText)
                .toolCallId(toolCallIdRef.get())
                .toolCallName(toolNameRef.get())
                .toolCallArgumentsJson(toolArgsBuf.toString())
                .aborted(aborted)
                .ttftMs(ttftRef.get())
                .promptTokens(estimateTokens(messages.toString()))
                .completionTokens(estimateTokens(finalText + toolArgsBuf))
                .build();
    }

    private void parseSseChunk(String data,
                               long startNs,
                               AtomicReference<Integer> ttftRef,
                               StringBuilder textBuf,
                               AtomicReference<String> toolCallIdRef,
                               AtomicReference<String> toolNameRef,
                               StringBuilder toolArgsBuf,
                               Consumer<String> onDelta) {
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return;
            }
            JsonNode delta = choices.get(0).path("delta");
            if (delta.hasNonNull("content")) {
                String chunk = delta.get("content").asText("");
                if (!chunk.isEmpty()) {
                    ttftRef.compareAndSet(null, (int) ((System.nanoTime() - startNs) / 1_000_000L));
                    textBuf.append(chunk);
                    onDelta.accept(chunk);
                }
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode tc = toolCalls.get(0);
                if (tc.hasNonNull("id") && toolCallIdRef.get() == null) {
                    toolCallIdRef.set(tc.get("id").asText());
                }
                JsonNode fn = tc.path("function");
                if (fn.hasNonNull("name") && toolNameRef.get() == null) {
                    toolNameRef.set(fn.get("name").asText());
                }
                if (fn.hasNonNull("arguments")) {
                    toolArgsBuf.append(fn.get("arguments").asText(""));
                }
            }
        } catch (Exception e) {
            log.debug("忽略非标准 SSE 片段: {}", data);
        }
    }

    private List<Map<String, Object>> buildOpenAiToolsSpec(List<AgentTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AgentTool tool : tools) {
            try {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tool.name());
                fn.put("description", tool.description());
                fn.put("parameters", objectMapper.readValue(tool.parametersSchemaJson(), Map.class));
                result.add(Map.of("type", "function", "function", fn));
            } catch (Exception e) {
                log.warn("构建 Tool Schema 失败: {}", tool.name(), e);
            }
        }
        return result;
    }

    /**
     * 无 API Key 时的本地仿真流式推理：完整演练工具调用、权限隔离查库、审批挂起与实时打断
     */
    private LlmTurnResult simulateStreamTurn(Long taskId,
                                             List<Map<String, Object>> messages,
                                             Consumer<String> onDelta) {
        long startNs = System.nanoTime();
        Map<String, Object> lastMsg = messages.get(messages.size() - 1);
        String lastRole = String.valueOf(lastMsg.get("role"));

        // 情况 A：刚刚拿到工具执行结果（role = tool），流式总结输出
        if ("tool".equals(lastRole)) {
            String toolName = String.valueOf(lastMsg.getOrDefault("name", ""));
            String observation = String.valueOf(lastMsg.getOrDefault("content", ""));
            String reply;
            if ("create_group_reminder".equals(toolName)) {
                reply = "[仿真流式模式] 审批已通过，工具执行完成：\n" + observation + "\n后续到达设定时间后系统可按该记录触发群提醒。";
            } else {
                reply = "[仿真流式模式] 我已在本会话权限范围内检索历史记录，整理摘要如下：\n\n"
                        + observation
                        + "\n\n以上内容严格限定在当前会话内检索，未跨群访问任何其他会话数据。";
            }
            return emitSimulatedChunks(taskId, startNs, reply, onDelta);
        }

        // 情况 B：首次收到用户 Prompt，识别意图决定是否触发 Tool Calling
        String userPrompt = String.valueOf(lastMsg.getOrDefault("content", ""));
        if (userPrompt.startsWith("[SUMMARY_MODE:")) {
            return emitSimulatedChunks(taskId, startNs, buildSimulatedDigest(userPrompt), onDelta);
        }
        if (containsAny(userPrompt, "提醒", "日程", "闹钟", "开会", "通知")) {
            String title = userPrompt.length() > 24 ? userPrompt.substring(0, 24) : userPrompt;
            String remindTime = CreateGroupReminderTool.resolveRelativeOrDefault(userPrompt).format(REMIND_FMT);
            String argsJson;
            try {
                argsJson = objectMapper.writeValueAsString(Map.of(
                        "title", title,
                        "content", "由 @AI 根据群聊指令创建：" + userPrompt,
                        "remindTime", remindTime
                ));
            } catch (Exception e) {
                argsJson = "{\"title\":\"群聊提醒\",\"content\":\"按时参会\"}";
            }
            return LlmTurnResult.builder()
                    .toolCallId("call_" + UUID.randomUUID().toString().substring(0, 8))
                    .toolCallName("create_group_reminder")
                    .toolCallArgumentsJson(argsJson)
                    .ttftMs(120)
                    .promptTokens(estimateTokens(userPrompt))
                    .completionTokens(40)
                    .build();
        }

        if (containsAny(userPrompt, "总结", "概括", "历史", "记录", "聊了什么", "刚才", "搜索", "查")) {
            return LlmTurnResult.builder()
                    .toolCallId("call_" + UUID.randomUUID().toString().substring(0, 8))
                    .toolCallName("search_chat_history")
                    .toolCallArgumentsJson("{\"keyword\":\"\",\"limit\":15}")
                    .ttftMs(110)
                    .promptTokens(estimateTokens(userPrompt))
                    .completionTokens(25)
                    .build();
        }

        // 情况 C：普通长回复，方便测试实时流式打字与点击「停止生成」打断
        String reply = "[仿真流式模式] 收到你的提问：「" + userPrompt + "」。\n"
                + "当前你正在体验基于 WebSocket 全双工的 AI Agent 异步流式架构：\n"
                + "1. 请求经由 Netty 网关 -> im-server 落库后通过 RabbitMQ 异步解耦给独立的 im-agent Worker，完全不阻塞普通聊天；\n"
                + "2. 生成过程中你可以随时点击下方「停止生成」按钮，后端会通过 Fanout 控制信号 + Disposable 毫秒级中断流式输出，并将已生成部分截断落库；\n"
                + "3. 试试发送「@AI 总结刚才聊了什么」体验权限隔离群聊检索，或发送「@AI 帮我建一个明早10点周会提醒」体验人机协同审批卡片！\n"
                + "（如需接入真实大模型，在 .env 中填入 LLM_API_KEY 并重启 AgentApplication 即可无缝切换。）";
        return emitSimulatedChunks(taskId, startNs, reply, onDelta);
    }

    private LlmTurnResult emitSimulatedChunks(Long taskId, long startNs, String fullText, Consumer<String> onDelta) {
        StringBuilder emitted = new StringBuilder();
        Integer ttftMs = null;
        boolean aborted = false;
        int chunkSize = 6;
        for (int i = 0; i < fullText.length(); i += chunkSize) {
            if (activeTaskRegistry.isCancelled(taskId)) {
                aborted = true;
                break;
            }
            String chunk = fullText.substring(i, Math.min(fullText.length(), i + chunkSize));
            if (ttftMs == null) {
                ttftMs = (int) ((System.nanoTime() - startNs) / 1_000_000L);
            }
            emitted.append(chunk);
            onDelta.accept(chunk);
            try {
                Thread.sleep(65L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                aborted = true;
                break;
            }
        }
        return LlmTurnResult.builder()
                .text(emitted.toString())
                .aborted(aborted)
                .ttftMs(ttftMs)
                .promptTokens(40)
                .completionTokens(estimateTokens(emitted.toString()))
                .build();
    }

    private String buildSimulatedDigest(String prompt) {
        boolean isUnread = prompt.startsWith("[SUMMARY_MODE:UNREAD]");
        String heading = isUnread ? "### ✨ 未读消息省流速览" : "### ✨ 选中消息 AI 提炼纪要";
        String[] lines = prompt.split("\n");
        List<String> items = new ArrayList<>();
        java.util.Set<String> participants = new java.util.LinkedHashSet<>();
        for (String line : lines) {
            if (line.startsWith("- ")) {
                items.add(line.substring(2).trim());
                int idx = line.indexOf(" (#");
                if (idx > 2) {
                    participants.add(line.substring(2, idx).trim());
                }
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(heading).append("\n\n");
        out.append("**覆盖消息数**：`").append(items.size()).append("` 条");
        if (!participants.isEmpty()) {
            out.append(" | **参与成员**：").append(String.join("、", participants));
        }
        out.append("\n\n#### 核心内容摘录\n");
        int shown = Math.min(items.size(), 6);
        for (int i = 0; i < shown; i++) {
            out.append(i + 1).append(". ").append(items.get(i)).append("\n");
        }
        if (items.size() > shown) {
            out.append("- *其余 ").append(items.size() - shown).append(" 条细节已归并省略*\n");
        }
        out.append("\n#### 后续建议\n");
        out.append("- 如需跟进具体结论，可悬停原消息一键「置顶」到群公告栏；\n");
        out.append("- 如需定时间节点，直接发送 `@AI 15秒后提醒大家...` 即可写入延迟队列自动通知。\n");
        return out.toString();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, text.length() / 2);
    }
}
