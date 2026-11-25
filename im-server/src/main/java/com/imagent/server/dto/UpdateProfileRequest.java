package com.imagent.server.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @Size(min = 1, max = 32, message = "昵称长度需在 1~32 个字符之间")
    private String nickname;

    @Size(max = 255, message = "头像地址过长")
    private String avatar;

    @Size(max = 120, message = "个性签名不能超过 120 个字符")
    private String signature;

    @Size(max = 255, message = "个人简介不能超过 255 个字符")
    private String bio;

    @Size(max = 16, message = "状态图标过长")
    private String statusEmoji;

    @Size(max = 32, message = "状态文案不能超过 32 个字符")
    private String statusText;
}
