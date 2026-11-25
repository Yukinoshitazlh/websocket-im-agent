package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.MessageVO;
import com.imagent.server.dto.ReactionGroupVO;
import com.imagent.server.dto.ToggleReactionRequest;
import com.imagent.server.service.MessageCollabService;
import com.imagent.server.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final MessageCollabService messageCollabService;

    /**
     * 游标分页历史记录：不传 beforeSeq 拉取最新；传入 beforeSeq 向上加载更早消息（无 OFFSET 深分页问题）
     */
    @GetMapping("/history")
    public Result<List<MessageVO>> history(
            @RequestParam Long conversationId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "30") int limit) {
        return Result.ok(messageService.listHistory(UserContext.getUserId(), conversationId, beforeSeq, limit));
    }

    /**
     * 会话内历史消息搜索
     */
    @GetMapping("/search")
    public Result<List<MessageVO>> search(
            @RequestParam Long conversationId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "30") int limit) {
        return Result.ok(messageService.searchMessages(UserContext.getUserId(), conversationId, keyword, limit));
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

    /**
     * 2 分钟内撤回自己发送的消息
     */
    @PostMapping("/{msgId}/recall")
    public Result<Void> recall(@PathVariable Long msgId) {
        messageService.recallMessage(UserContext.getUserId(), msgId);
        return Result.ok();
    }

    /**
     * 飞书式消息表情表态（幂等 Toggle，实时广播 MSG_REACTION）
     */
    @PostMapping("/{msgId}/reactions")
    public Result<List<ReactionGroupVO>> toggleReaction(
            @PathVariable Long msgId,
            @Valid @RequestBody ToggleReactionRequest request) {
        return Result.ok(messageCollabService.toggleReaction(UserContext.getUserId(), msgId, request.getEmoji()));
    }
}
