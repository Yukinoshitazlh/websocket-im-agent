package com.imagent.common.protocol.payload;

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
    private Long taskId;
    private Long approvalId;
    private Long conversationId;
    private String actionName;
    private String summary;
    private Long expireAt;
}
