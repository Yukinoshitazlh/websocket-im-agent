package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.dto.InternalAgentApprovalRequest;
import com.imagent.server.dto.InternalAgentCancelRequest;
import com.imagent.server.service.AgentBridgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 im-gateway 调用的内部 Agent 控制接口（实时打断 & 审批决策）
 */
@RestController
@RequestMapping("/internal/agent")
@RequiredArgsConstructor
public class InternalAgentController {

    private final AgentBridgeService agentBridgeService;

    @PostMapping("/cancel")
    public Result<Void> cancel(@Valid @RequestBody InternalAgentCancelRequest request) {
        agentBridgeService.requestCancel(request.getUserId(), request.getTaskId(), request.getConversationId());
        return Result.ok();
    }

    @PostMapping("/approval")
    public Result<Void> approval(@Valid @RequestBody InternalAgentApprovalRequest request) {
        agentBridgeService.handleApprovalDecision(
                request.getUserId(),
                request.getTaskId(),
                request.getApprovalId(),
                Boolean.TRUE.equals(request.getApproved()),
                request.getComment()
        );
        return Result.ok();
    }
}
