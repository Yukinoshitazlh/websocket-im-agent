package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    /** 支持输入邮箱或用户名（兼容老字段 username） */
    private String account;
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String captchaId;
    private String captchaCode;

    public String resolveLoginAccount() {
        if (account != null && !account.isBlank()) {
            return account.trim();
        }
        return username == null ? "" : username.trim();
    }
}
