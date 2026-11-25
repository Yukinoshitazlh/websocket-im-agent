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
    private String nickname;
    private String avatar;
    private String signature;
    private String bio;
    private String statusEmoji;
    private String statusText;
    private Boolean online;
    private Integer userType;
}
