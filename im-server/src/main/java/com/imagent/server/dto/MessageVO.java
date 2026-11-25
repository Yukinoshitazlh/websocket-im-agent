package com.imagent.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private List<ReactionGroupVO> reactions;
    private Integer status;
    private Long createdAt;
}
