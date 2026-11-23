package com.imagent.common.protocol.payload;

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
    private Long conversationId;
    private String clientMsgId;
    private String msgType;
    private String content;
    private Long replyToMsgId;
    private Boolean mentionAgent;
}
