package com.imagent.server.dto;

import com.imagent.server.entity.ImAgentTask;
import com.imagent.server.entity.ImGroupReminder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话级 AI Agent 可观测性汇总面板数据
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMetricsVO {
    private int totalTasks;
    private int doneCount;
    private int abortedCount;
    private int waitingApprovalCount;
    private int failedCount;
    private Integer avgTtftMs;
    private Integer avgTotalMs;
    private int totalPromptTokens;
    private int totalCompletionTokens;
    private List<ImAgentTask> recentTasks;
    private List<ImGroupReminder> reminders;
}
