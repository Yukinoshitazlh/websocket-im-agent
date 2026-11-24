package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
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
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taskId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private String stage;
    private String delta;
    private String toolName;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    private Long seq;
    private String errorMessage;
}
