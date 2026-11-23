package com.imagent.common.protocol.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Agent 全双工流式下行帧载荷（AGENT_STREAM）
 * stage: thinking | tool_call | delta | done | aborted | error
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamPayload {
    private Long taskId;
    private Long conversationId;
    private String stage;
    private String delta;
    private String toolName;
    private Long msgId;
    private Long seq;
    private String errorMessage;
}
