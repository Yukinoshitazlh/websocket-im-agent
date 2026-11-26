package com.imagent.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendEmailCodeVO {
    private long cooldownSeconds;
    private long expireSeconds;
    /** 仅本地 dev 模式且未启用 SMTP 时回显，方便联调；生产恒为 null */
    private String devCodeHint;
}
