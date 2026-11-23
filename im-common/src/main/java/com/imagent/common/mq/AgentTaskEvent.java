package com.imagent.common.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步 Agent 执行任务事件（im-server -> im.agent.task.queue -> im-agent）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskEvent {
    private Long taskId;
    private Long conversationId;
    private Long triggerUserId;
    private Long triggerMsgId;
    private String userPrompt;
}
