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
public class UserProfileVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String username;
    private String email;
    private String nickname;
    private String avatar;
    private String signature;
    private String bio;
    private String statusEmoji;
    private String statusText;
    private Boolean online;
    private Integer userType;
    /** SELF / FRIEND / PENDING_OUT / PENDING_IN / BLOCKED / BLOCKED_BY / NONE */
    private String relationStatus;
    /** 当 relationStatus=PENDING_IN 时附带待处理申请单 ID，便于一键同意 */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long pendingRequestId;
}
