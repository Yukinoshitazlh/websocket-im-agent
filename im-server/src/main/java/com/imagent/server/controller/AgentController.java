package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.entity.ImAgentTask;
import com.imagent.server.entity.ImGroupReminder;
import com.imagent.server.service.AgentBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 任务可观测性指标 & 群提醒查询接口
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
}
