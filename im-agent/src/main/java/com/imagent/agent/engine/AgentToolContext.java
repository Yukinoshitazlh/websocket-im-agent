package com.imagent.agent.engine;

import lombok.Builder;
import lombok.Data;

/**
 * Tool 执行上下文：强制携带会话与触发者身份，用于权限隔离校验
 */
@Data
@Builder
public class AgentToolContext {
    private Long taskId;
    private Long conversationId;
    private Long triggerUserId;
}
