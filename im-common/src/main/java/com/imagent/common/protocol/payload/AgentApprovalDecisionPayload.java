package com.imagent.common.protocol.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端上行人机审批决策载荷（AGENT_APPROVAL）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentApprovalDecisionPayload {
    private Long taskId;
    private Long approvalId;
    private Boolean approved;
    private String comment;
}
