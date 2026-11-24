package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.MessageVO;
import com.imagent.server.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/history")
    public Result<List<MessageVO>> history(
            @RequestParam Long conversationId,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(messageService.listHistory(UserContext.getUserId(), conversationId, limit));
    }
}
