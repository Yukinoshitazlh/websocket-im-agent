package com.imagent.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "im.security")
public class ServerSecurityProperties {
    private String jwtSecret = "im-agent-local-dev-jwt-secret-key-32bytes!";
    private long jwtExpireSeconds = 604800L;
    private String internalToken = "im-agent-local-dev-internal-token-32bytes!";
    /** 逗号分隔的允许跨域来源列表（生产环境严格配置为正式域名） */
    private String corsAllowedOrigins = "http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080";
    private AuthPolicy auth = new AuthPolicy();

    public List<String> resolveCorsAllowedOriginList() {
        if (corsAllowedOrigins == null || corsAllowedOrigins.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Data
    public static class AuthPolicy {
        /** 是否开启图形验证码 */
        private boolean requireCaptcha = true;
        /** 注册时是否强制校验邮箱验证码 */
        private boolean requireEmailCode = true;
        /** 可选内测邀请码；留空表示开放注册 */
        private String inviteCode = "";
        /** 一次性 WebSocket 握手票据有效期（秒） */
        private long wsTicketTtlSeconds = 30L;
        /** 连续登录失败触发锁定的最大次数 */
        private int loginMaxFailures = 5;
        /** 登录锁定持续分钟数 */
        private int loginLockMinutes = 15;
        /** 单 IP 每分钟最大登录尝试次数 */
        private int ipLoginMaxPerMinute = 30;
        /** 单 IP 每小时最大注册次数 */
        private int ipRegisterMaxPerHour = 10;
        /** 单 IP 每小时最大发送邮箱验证码次数 */
        private int ipEmailCodeMaxPerHour = 10;
        /** 是否启用真实 SMTP 邮件发送（本地开发默认关闭，在服务端日志输出验证码） */
        private boolean mailEnabled = false;
        /** 发件人邮箱 */
        private String mailFrom = "no-reply@pulseim.local";
        /** 本地开发模式下是否在发送邮箱验证码响应中回显验证码，方便无 SMTP 联调（生产必须 false） */
        private boolean devExposeEmailCode = true;
        /** 是否在登录页展示演示快捷体验账号（生产必须 false） */
        private boolean allowDemoQuickLogin = true;
        /** 是否在前端设置菜单中暴露「面试演练模式」开关（生产必须 false） */
        private boolean showDemoMode = true;
    }
}
