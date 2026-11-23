package com.imagent.common.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 实时控制信号（广播到 im.agent.ctrl.exchange）
 * controlType: CANCEL | RESUME_APPROVED | RESUME_REJECTED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentControlEvent {
    private String controlType;
    private Long taskId;
    private Long approvalId;
    private Long operatorUserId;
    private String comment;
}
