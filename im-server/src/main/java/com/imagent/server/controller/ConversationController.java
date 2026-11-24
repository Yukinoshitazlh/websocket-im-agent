package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.ConversationVO;
import com.imagent.server.dto.CreateDirectConvRequest;
import com.imagent.server.dto.CreateGroupConvRequest;
import com.imagent.server.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping("/direct")
    public Result<ConversationVO> createDirect(@Valid @RequestBody CreateDirectConvRequest request) {
        return Result.ok(conversationService.createOrGetDirectConversation(
                UserContext.getUserId(), request.getTargetUserId()));
    }

    @PostMapping("/group")
    public Result<ConversationVO> createGroup(@Valid @RequestBody CreateGroupConvRequest request) {
        return Result.ok(conversationService.createGroupConversation(
                UserContext.getUserId(), request));
    }

    @GetMapping
    public Result<List<ConversationVO>> listMine() {
        return Result.ok(conversationService.listMyConversations(UserContext.getUserId()));
    }
}
