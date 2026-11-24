package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端上行发送消息载荷（CHAT_SEND）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSendPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private String clientMsgId;
    private String msgType;
    private String content;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long replyToMsgId;
    private Boolean mentionAgent;
}
