package com.imagent.server.dto;

import com.imagent.common.protocol.payload.ChatSendPayload;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * im-gateway 转发上行聊天消息给 im-server 的内部请求体
 */
@Data
public class InternalChatSendRequest {
    @NotNull
    private Long senderId;
    @NotNull
    private ChatSendPayload payload;
}
