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
public class ConversationVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private Integer type;
    private String title;
    private Long currentSeq;
    private Long lastReadSeq;
    private Long unreadCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long peerUserId;
    private Boolean peerOnline;
    private String lastMsgPreview;
    private Long lastMsgTime;
}
