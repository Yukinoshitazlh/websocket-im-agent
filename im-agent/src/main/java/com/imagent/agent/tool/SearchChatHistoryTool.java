package com.imagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.engine.AgentTool;
import com.imagent.agent.engine.AgentToolContext;
import com.imagent.agent.entity.ChatHistorySnippet;
import com.imagent.agent.mapper.ConversationReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 权限隔离群聊记忆检索工具（只读工具，无需审批）
 * 安全关键点：查询范围强制取自可信上下文 context.getConversationId()，
 * 绝不采信大模型 Prompt 传入的 conversationId，防止提示词注入导致跨群越权泄漏隐私。
 */
@Component
@RequiredArgsConstructor
public class SearchChatHistoryTool implements AgentTool {

    private final ConversationReadMapper conversationReadMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "search_chat_history";
    }

    @Override
    public String description() {
        return "检索当前会话的历史聊天记录，用于回答历史讨论结论或总结群聊内容（仅限当前会话权限范围）。";
    }

    @Override
    public String parametersSchemaJson() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "keyword": {
                      "type": "string",
                      "description": "可选检索关键词；留空则返回当前会话最近聊天记录用于总结"
                    },
                    "limit": {
                      "type": "integer",
                      "description": "返回消息条数上限，默认 15，最大 30"
                    }
                  }
                }
                """;
    }

    @Override
    public String execute(AgentToolContext context, String argumentsJson) {
        String keyword = "";
        int limit = 15;
        try {
            if (argumentsJson != null && !argumentsJson.isBlank()) {
                JsonNode node = objectMapper.readTree(argumentsJson);
                if (node.hasNonNull("keyword")) {
                    keyword = node.get("keyword").asText("").trim();
                }
                if (node.hasNonNull("limit")) {
                    limit = Math.min(Math.max(node.get("limit").asInt(15), 1), 30);
                }
            }
        } catch (Exception ignored) {
        }

        // 强制绑定当前会话 ID 进行权限隔离检索
        List<ChatHistorySnippet> rows = keyword.isEmpty()
                ? conversationReadMapper.selectRecentMessages(context.getConversationId(), limit)
                : conversationReadMapper.searchMessagesByKeyword(context.getConversationId(), keyword, limit);

        if (rows.isEmpty()) {
            return keyword.isEmpty() ? "当前会话暂无历史消息。" : ("在当前会话中未检索到包含「" + keyword + "」的历史消息。");
        }
        Collections.reverse(rows);
        return rows.stream()
                .map(r -> String.format("[#%d %s]: %s", r.getSeq(), r.getSenderNickname(), r.getContent()))
                .collect(Collectors.joining("\n"));
    }
}
