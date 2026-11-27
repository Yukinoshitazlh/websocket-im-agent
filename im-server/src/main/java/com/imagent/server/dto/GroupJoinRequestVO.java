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
public class GroupJoinRequestVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long inviterId;
    private String inviterNickname;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long inviteeId;
    private String inviteeUsername;
    private String inviteeNickname;
    private String inviteeAvatar;
    private String status;
    private Long createdAt;
}
