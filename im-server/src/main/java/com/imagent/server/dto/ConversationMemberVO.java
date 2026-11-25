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
public class ConversationMemberVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String signature;
    private String statusEmoji;
    private String statusText;
    private Integer role;
    private Boolean online;
    private Long lastReadSeq;
}
