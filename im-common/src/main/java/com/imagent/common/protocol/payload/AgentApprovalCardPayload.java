package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI Agent 写操作人机审批卡片下行载荷（AGENT_APPROVAL_CARD）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApprovalCardPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long taskId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long approvalId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private String actionName;
    private String summary;
    private Long expireAt;
}
