package com.imagent.common.protocol.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端上行实时打断载荷（AGENT_CANCEL）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCancelPayload {
    private Long taskId;
    private Long conversationId;
}
