package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 下行实时聊天消息推送载荷（CHAT_PUSH）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatPushPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long senderId;
    private String senderNickname;
    private String senderAvatar;
    private String clientMsgId;
    private Long seq;
    private String msgType;
    private String content;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long replyToMsgId;
    private String replyToSender;
    private String replyToContent;
    private String extra;
    private List<String> atUserIds;
    private Integer status;
    private Long createdAt;
}
