package com.imagent.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long senderId;
    private String senderNickname;
    private String clientMsgId;
    private Long seq;
    private String msgType;
    private String content;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long replyToMsgId;
    private Long createdAt;
}
