package com.imagent.common.protocol.payload;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 下行实时聊天消息推送载荷（CHAT_PUSH）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatPushPayload {
    private Long msgId;
    private Long conversationId;
    private Long senderId;
    private String senderNickname;
    private Long seq;
    private String msgType;
    private String content;
    private Long replyToMsgId;
    private Long createdAt;
}
