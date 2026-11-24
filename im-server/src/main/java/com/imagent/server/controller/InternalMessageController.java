package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.common.protocol.payload.ServerAckPayload;
import com.imagent.server.dto.InternalChatSendRequest;
import com.imagent.server.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 im-gateway 调用的内部上行消息受理接口
 */
@RestController
@RequestMapping("/internal/messages")
@RequiredArgsConstructor
public class InternalMessageController {

    private final MessageService messageService;

    @PostMapping("/send")
    public Result<ServerAckPayload> send(@Valid @RequestBody InternalChatSendRequest request) {
        return Result.ok(messageService.processIncomingMessage(request.getSenderId(), request.getPayload()));
    }
}
