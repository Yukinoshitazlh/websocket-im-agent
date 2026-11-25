package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.common.protocol.payload.ServerAckPayload;
import com.imagent.server.dto.InternalAgentReplyRequest;
import com.imagent.server.dto.InternalChatSendRequest;
import com.imagent.server.dto.InternalMsgAckRequest;
import com.imagent.server.dto.InternalTypingRequest;
import com.imagent.server.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 im-gateway 与 im-agent 调用的内部消息受理接口
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

    @PostMapping("/ack")
    public Result<Void> ack(@Valid @RequestBody InternalMsgAckRequest request) {
        messageService.ackRead(request.getUserId(), request.getConversationId(), request.getSeq());
        return Result.ok();
    }

    @PostMapping("/agent-reply")
    public Result<ServerAckPayload> agentReply(@Valid @RequestBody InternalAgentReplyRequest request) {
        return Result.ok(messageService.persistAgentReply(request));
    }

    @PostMapping("/typing")
    public Result<Void> typing(@Valid @RequestBody InternalTypingRequest request) {
        messageService.broadcastTyping(request.getUserId(), request.getConversationId(), Boolean.TRUE.equals(request.getTyping()));
        return Result.ok();
    }
}
