package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * im-gateway 转发客户端已读回执（MSG_ACK）的内部请求体
 */
@Data
public class InternalMsgAckRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long conversationId;
    @NotNull
    private Long seq;
}
