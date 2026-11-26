package com.imagent.server.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 个字符")
    private String email;

    /** 邮箱 6 位验证码（开启邮箱验证时必填） */
    private String emailCode;

    /** 可选用户名；未填时由后端基于邮箱前缀自动生成唯一账号 */
    @Size(max = 32, message = "账号长度不能超过 32 个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在 8~64 个字符之间")
    private String password;

    @Size(max = 32, message = "昵称不能超过 32 个字符")
    private String nickname;

    /** 图形验证码（关闭邮箱验证码且开启图形验证码时必填） */
    private String captchaId;
    private String captchaCode;

    /** 可选内测邀请码（服务端配置了邀请码时必填） */
    private String inviteCode;
}
