package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.MessageVO;
import com.imagent.server.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 断线重连离线增量同步接口：拉取 seq > afterSeq 的所有缺失消息
     */
    @GetMapping("/sync")
    public Result<List<MessageVO>> sync(
            @RequestParam Long conversationId,
            @RequestParam(defaultValue = "0") Long afterSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(messageService.syncAfterSeq(UserContext.getUserId(), conversationId, afterSeq, limit));
    }

    /**
     * HTTP 方式上报已读水位（配合 WebSocket MSG_ACK 双通道兜底）
     */
    @PostMapping("/ack")
    public Result<Void> ackRead(@RequestParam Long conversationId, @RequestParam Long seq) {
        messageService.ackRead(UserContext.getUserId(), conversationId, seq);
        return Result.ok();
    }
}
