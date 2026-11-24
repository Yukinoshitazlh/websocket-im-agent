package com.imagent.agent.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 人机审批挂起时的状态机快照（持久化至 im_agent_task.checkpoint_state）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCheckpoint {
    private Long taskId;
    private Long conversationId;
    private Long triggerUserId;
    private Long triggerMsgId;
    private Long approvalId;
    private int currentStep;
    private String pendingToolCallId;
    private String pendingToolName;
    private String pendingToolArgsJson;
    private List<Map<String, Object>> messages;
}
