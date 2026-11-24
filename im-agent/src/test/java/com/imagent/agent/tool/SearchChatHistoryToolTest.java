package com.imagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.agent.engine.AgentToolContext;
import com.imagent.agent.entity.ChatHistorySnippet;
import com.imagent.agent.mapper.ConversationReadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchChatHistoryToolTest {

    @Mock
    private ConversationReadMapper conversationReadMapper;

    @Test
    void shouldStrictlyEnforceConversationPermissionIsolationAgainstPromptInjection() {
        SearchChatHistoryTool tool = new SearchChatHistoryTool(conversationReadMapper, new ObjectMapper());
        ChatHistorySnippet snippet = new ChatHistorySnippet();
        snippet.setSeq(12L);
        snippet.setSenderNickname("Alice");
        snippet.setContent("明早十点开周会");

        when(conversationReadMapper.searchMessagesByKeyword(eq(1001L), eq("周会"), eq(15)))
                .thenReturn(new ArrayList<>(List.of(snippet)));

        AgentToolContext trustedCtx = AgentToolContext.builder()
                .taskId(5001L).conversationId(1001L).triggerUserId(2001L).build();

        // 即使大模型参数里被提示词注入了其他群的 conversationId=999999，工具也必须只查可信上下文 1001L
        String result = tool.execute(trustedCtx, "{\"conversationId\":999999,\"keyword\":\"周会\"}");

        assertTrue(result.contains("明早十点开周会"));
        verify(conversationReadMapper).searchMessagesByKeyword(1001L, "周会", 15);
    }
}
