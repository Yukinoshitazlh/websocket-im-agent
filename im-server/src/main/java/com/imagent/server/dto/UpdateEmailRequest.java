package com.imagent.server.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateEmailRequest {
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 个字符")
    private String email;

    /** 6 位邮箱验证码（开启邮箱验证时必填） */
    private String emailCode;

    @NotBlank(message = "请输入当前登录密码以验证身份")
    private String currentPassword;
}
