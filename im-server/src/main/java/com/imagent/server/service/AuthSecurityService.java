package com.imagent.server.service;

import com.imagent.common.constant.RedisKeys;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.dto.AuthConfigVO;
import com.imagent.server.dto.CaptchaVO;
import com.imagent.server.dto.SendEmailCodeVO;
import com.imagent.server.dto.WsTicketVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * 认证防刷与安全中心：
 * 1. 纯 Java SVG 图形验证码（零 AWT 无头字体依赖，适配最小化 Linux JRE）；
 * 2. 邮箱验证码发送（支持真实 SMTP，未启用时开发模式日志输出防卡死）与试错计数限制；
 * 3. IP 固定窗口限流（登录/注册/发邮件）；
 * 4. 账号连续输错密码临时锁定；
 * 5. 可选内测邀请码准入校验；
 * 6. 30 秒一次性 WebSocket 握手票据签发（避免长期 JWT 出现在 WS URL 中）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSecurityService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 去除易混淆字符 0/O/1/I/l */
    private static final String CAPTCHA_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Duration CAPTCHA_TTL = Duration.ofMinutes(5);
    private static final Duration EMAIL_CODE_TTL = Duration.ofMinutes(10);
    private static final Duration EMAIL_COOLDOWN = Duration.ofSeconds(60);
    private static final int EMAIL_CODE_MAX_ATTEMPTS = 5;

    private final StringRedisTemplate redisTemplate;
    private final ServerSecurityProperties securityProperties;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    public AuthConfigVO getPublicAuthConfig() {
        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        return AuthConfigVO.builder()
                .requireCaptcha(auth.isRequireCaptcha())
                .requireEmailCode(auth.isRequireEmailCode())
                .inviteCodeRequired(auth.getInviteCode() != null && !auth.getInviteCode().isBlank())
                .allowDemoQuickLogin(auth.isAllowDemoQuickLogin())
                .showDemoMode(auth.isShowDemoMode())
                .build();
    }

    /**
     * 生成纯 SVG 图形验证码（无外部字体/AWT 依赖）
     */
    public CaptchaVO generateCaptcha() {
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(CAPTCHA_ALPHABET.charAt(RANDOM.nextInt(CAPTCHA_ALPHABET.length())));
        }
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(
                RedisKeys.captchaCode(captchaId),
                code.toString().toUpperCase(Locale.ROOT),
                CAPTCHA_TTL
        );

        String svg = buildCaptchaSvg(code.toString());
        String dataUri = "data:image/svg+xml;base64,"
                + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return CaptchaVO.builder()
                .captchaId(captchaId)
                .svgDataUri(dataUri)
                .expireSeconds(CAPTCHA_TTL.toSeconds())
                .build();
    }

    /**
     * 原子消费校验图形验证码（防重放攻击）
     */
    public void verifyCaptcha(String captchaId, String captchaCode) {
        if (!securityProperties.getAuth().isRequireCaptcha()) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入图形验证码");
        }
        String key = RedisKeys.captchaCode(captchaId.trim());
        String expected = redisTemplate.opsForValue().getAndDelete(key);
        if (expected == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图形验证码已过期，请点击刷新");
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图形验证码不正确，请重新输入");
        }
    }

    /**
     * 已登录用户绑定/更换邮箱时发送 6 位验证码（走 IP 限流 + 60 秒冷却，无需重复填图形验证码）
     */
    public SendEmailCodeVO sendBindEmailCode(String rawEmail, String clientIp) {
        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        checkIpRateLimit("email_code", clientIp, auth.getIpEmailCodeMaxPerHour(), Duration.ofHours(1));
        return dispatchEmailCode(
                rawEmail,
                "【PulseIM】绑定 / 更换邮箱验证码",
                "您正在 PulseIM 绑定或更换账号邮箱，本次验证码为："
        );
    }

    /**
     * 发送邮箱 6 位验证码（先做 IP 限流 + 图形验证码校验 + 60 秒邮箱冷却）
     */
    public SendEmailCodeVO sendEmailVerificationCode(String rawEmail, String captchaId, String captchaCode, String clientIp) {
        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        checkIpRateLimit("email_code", clientIp, auth.getIpEmailCodeMaxPerHour(), Duration.ofHours(1));
        verifyCaptcha(captchaId, captchaCode);
        return dispatchEmailCode(
                rawEmail,
                "【PulseIM】邮箱注册验证码",
                "您正在注册 PulseIM 账号，本次验证码为："
        );
    }

    private SendEmailCodeVO dispatchEmailCode(String rawEmail, String subject, String bodyPrefix) {
        ServerSecurityProperties.AuthPolicy auth = securityProperties.getAuth();
        String email = normalizeEmail(rawEmail);
        String cooldownKey = RedisKeys.emailCodeCooldown(email);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", EMAIL_COOLDOWN);
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BizException(ErrorCode.RATE_LIMITED, "验证码发送过于频繁，请 60 秒后再试");
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redisTemplate.opsForValue().set(RedisKeys.emailCode(email), code, EMAIL_CODE_TTL);
        redisTemplate.delete(RedisKeys.emailCodeAttempts(email));

        String devHint = null;
        if (auth.isMailEnabled()) {
            JavaMailSender sender = mailSenderProvider.getIfAvailable();
            if (sender == null) {
                redisTemplate.delete(cooldownKey);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "邮件服务未配置，请联系管理员检查 SMTP 参数");
            }
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(auth.getMailFrom());
                msg.setTo(email);
                msg.setSubject(subject);
                msg.setText("您好！\n\n" + bodyPrefix + code
                        + "\n\n验证码 10 分钟内有效，请勿泄露给他人。如非本人操作请忽略本邮件。");
                sender.send(msg);
                log.info("已向邮箱 {} 发送验证码: subject={}", maskEmail(email), subject);
            } catch (Exception e) {
                redisTemplate.delete(cooldownKey);
                log.error("发送验证码邮件失败: email={}", maskEmail(email), e);
                throw new BizException(ErrorCode.INTERNAL_ERROR, "发送邮件失败，请稍后重试或检查邮箱地址");
            }
        } else {
            log.info("[DEV 邮件模拟] 验证码已生成 -> email={}, code={} (10分钟有效)", email, code);
            if (auth.isDevExposeEmailCode()) {
                devHint = code;
            }
        }

        return SendEmailCodeVO.builder()
                .cooldownSeconds(EMAIL_COOLDOWN.toSeconds())
                .expireSeconds(EMAIL_CODE_TTL.toSeconds())
                .devCodeHint(devHint)
                .build();
    }

    /**
     * 校验邮箱 6 位验证码（带防爆破试错上限）
     */
    public void verifyEmailCode(String rawEmail, String providedCode) {
        if (!securityProperties.getAuth().isRequireEmailCode()) {
            return;
        }
        if (providedCode == null || providedCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请输入邮箱验证码");
        }
        String email = normalizeEmail(rawEmail);
        String codeKey = RedisKeys.emailCode(email);
        String expected = redisTemplate.opsForValue().get(codeKey);
        if (expected == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱验证码已过期或未发送，请重新获取");
        }
        if (!expected.equals(providedCode.trim())) {
            String tryKey = RedisKeys.emailCodeAttempts(email);
            Long attempts = redisTemplate.opsForValue().increment(tryKey);
            if (attempts != null && attempts == 1L) {
                redisTemplate.expire(tryKey, EMAIL_CODE_TTL);
            }
            if (attempts != null && attempts >= EMAIL_CODE_MAX_ATTEMPTS) {
                redisTemplate.delete(codeKey);
                redisTemplate.delete(tryKey);
                throw new BizException(ErrorCode.BAD_REQUEST, "邮箱验证码连续输错次数过多，已作废，请重新获取");
            }
            throw new BizException(ErrorCode.BAD_REQUEST, "邮箱验证码不正确");
        }
        redisTemplate.delete(codeKey);
        redisTemplate.delete(RedisKeys.emailCodeAttempts(email));
    }

    /**
     * 校验可选内测邀请码
     */
    public void verifyInviteCodeIfRequired(String providedInviteCode) {
        String expected = securityProperties.getAuth().getInviteCode();
        if (expected == null || expected.isBlank()) {
            return;
        }
        if (providedInviteCode == null || providedInviteCode.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前处于内测阶段，请输入邀请码");
        }
        boolean matched = MessageDigest.isEqual(
                expected.trim().getBytes(StandardCharsets.UTF_8),
                providedInviteCode.trim().getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            throw new BizException(ErrorCode.BAD_REQUEST, "内测邀请码无效");
        }
    }

    /**
     * 校验密码强度：至少 8 位且同时包含字母与数字
     */
    public void assertPasswordStrength(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码长度需在 8~64 位之间");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码需至少包含字母和数字");
        }
    }

    /**
     * IP 固定窗口限流
     */
    public void checkIpRateLimit(String action, String clientIp, int limit, Duration window) {
        if (limit <= 0) {
            return;
        }
        String safeIp = (clientIp == null || clientIp.isBlank()) ? "unknown" : clientIp.trim();
        String key = RedisKeys.ipRateLimit(action, safeIp);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > limit) {
            throw new BizException(ErrorCode.RATE_LIMITED, "当前 IP 请求过于频繁，请稍后再试");
        }
    }

    public void checkLoginNotLocked(String account) {
        int maxFailures = securityProperties.getAuth().getLoginMaxFailures();
        if (maxFailures <= 0) {
            return;
        }
        String raw = redisTemplate.opsForValue().get(RedisKeys.loginFailCount(account.toLowerCase(Locale.ROOT)));
        if (raw != null && Integer.parseInt(raw) >= maxFailures) {
            throw new BizException(ErrorCode.RATE_LIMITED,
                    "密码连续错误次数过多，账号已临时锁定 " + securityProperties.getAuth().getLoginLockMinutes() + " 分钟");
        }
    }

    public void recordLoginFailure(String account) {
        int lockMinutes = Math.max(1, securityProperties.getAuth().getLoginLockMinutes());
        String key = RedisKeys.loginFailCount(account.toLowerCase(Locale.ROOT));
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofMinutes(lockMinutes));
        }
    }

    public void clearLoginFailures(String account) {
        redisTemplate.delete(RedisKeys.loginFailCount(account.toLowerCase(Locale.ROOT)));
    }

    /**
     * 签发 30 秒一次性 WebSocket 握手票据
     */
    public WsTicketVO issueWsTicket(Long userId) {
        byte[] randomBytes = new byte[24];
        RANDOM.nextBytes(randomBytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        long ttl = Math.max(10L, securityProperties.getAuth().getWsTicketTtlSeconds());
        redisTemplate.opsForValue().set(RedisKeys.wsTicket(ticket), String.valueOf(userId), Duration.ofSeconds(ttl));
        return WsTicketVO.builder()
                .ticket(ticket)
                .expireSeconds(ttl)
                .build();
    }

    public String normalizeEmail(String rawEmail) {
        return rawEmail == null ? "" : rawEmail.trim().toLowerCase(Locale.ROOT);
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) {
            return "***" + (at >= 0 ? email.substring(at) : "");
        }
        return email.substring(0, 2) + "***" + email.substring(at);
    }

    private String buildCaptchaSvg(String code) {
        String[] palette = {"#2563eb", "#7c3aed", "#0d9488", "#d97706", "#e11d48"};
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"124\" height=\"42\" viewBox=\"0 0 124 42\">")
                .append("<defs><linearGradient id=\"bg\" x1=\"0\" y1=\"0\" x2=\"1\" y2=\"1\">")
                .append("<stop offset=\"0%\" stop-color=\"#eef4ff\"/><stop offset=\"100%\" stop-color=\"#f5f3ff\"/>")
                .append("</linearGradient></defs>")
                .append("<rect width=\"124\" height=\"42\" rx=\"10\" fill=\"url(#bg)\"/>");

        for (int i = 0; i < 4; i++) {
            int x1 = RANDOM.nextInt(30);
            int y1 = RANDOM.nextInt(42);
            int cx = 30 + RANDOM.nextInt(64);
            int cy = RANDOM.nextInt(42);
            int x2 = 94 + RANDOM.nextInt(30);
            int y2 = RANDOM.nextInt(42);
            String color = palette[RANDOM.nextInt(palette.length)];
            sb.append("<path d=\"M").append(x1).append(' ').append(y1)
                    .append(" Q").append(cx).append(' ').append(cy)
                    .append(' ').append(x2).append(' ').append(y2)
                    .append("\" stroke=\"").append(color)
                    .append("\" stroke-opacity=\"0.28\" stroke-width=\"1.4\" fill=\"none\"/>");
        }

        for (int i = 0; i < 16; i++) {
            int cx = RANDOM.nextInt(124);
            int cy = RANDOM.nextInt(42);
            String color = palette[RANDOM.nextInt(palette.length)];
            sb.append("<circle cx=\"").append(cx).append("\" cy=\"").append(cy)
                    .append("\" r=\"1.2\" fill=\"").append(color).append("\" fill-opacity=\"0.35\"/>");
        }

        for (int i = 0; i < code.length(); i++) {
            int x = 18 + i * 25 + RANDOM.nextInt(5);
            int y = 28 + RANDOM.nextInt(5) - 2;
            int rot = RANDOM.nextInt(28) - 14;
            String color = palette[RANDOM.nextInt(palette.length)];
            sb.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" fill=\"").append(color)
                    .append("\" font-family=\"ui-monospace,SFMono-Regular,Consolas,monospace\" font-size=\"22\" font-weight=\"800\" transform=\"rotate(")
                    .append(rot).append(' ').append(x).append(' ').append(y).append(")\">")
                    .append(code.charAt(i))
                    .append("</text>");
        }
        sb.append("</svg>");
        return sb.toString();
    }
}
