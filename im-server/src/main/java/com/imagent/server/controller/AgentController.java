package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.AgentMetricsVO;
import com.imagent.server.dto.AgentSummarizeRequest;
import com.imagent.server.entity.ImAgentTask;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.service.AgentBridgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 任务可观测性指标、群提醒查询 & 飞书未读/多选消息一键总结触发接口
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentBridgeService agentBridgeService;

    @GetMapping("/tasks")
    public Result<List<ImAgentTask>> tasks(@RequestParam Long conversationId) {
        return Result.ok(agentBridgeService.listRecentTasks(UserContext.getUserId(), conversationId));
    }

    @GetMapping("/reminders")
    public Result<List<ImGroupReminder>> reminders(@RequestParam Long conversationId) {
        return Result.ok(agentBridgeService.listGroupReminders(UserContext.getUserId(), conversationId));
    }

    @GetMapping("/metrics")
    public Result<AgentMetricsVO> metrics(@RequestParam Long conversationId) {
        return Result.ok(agentBridgeService.getConversationMetrics(UserContext.getUserId(), conversationId));
    }

    /**
     * 触发未读省流总结（UNREAD）或勾选消息提炼（SELECTED），走异步 RabbitMQ + SSE 流式推送
     */
    @PostMapping("/summarize")
    public Result<String> summarize(@Valid @RequestBody AgentSummarizeRequest request) {
        Long taskId = agentBridgeService.enqueueSummaryTask(UserContext.getUserId(), request);
        return Result.ok(String.valueOf(taskId));
    }
}
