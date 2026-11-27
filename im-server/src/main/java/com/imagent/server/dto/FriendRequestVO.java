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
public class FriendRequestVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    /** INCOMING / OUTGOING */
    private String direction;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long peerUserId;
    private String peerUsername;
    private String peerNickname;
    private String peerAvatar;
    private String peerSignature;
    private String remark;
    private String status;
    private Long createdAt;
}
