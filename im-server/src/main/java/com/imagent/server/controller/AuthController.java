package com.imagent.server.controller;

import com.imagent.common.model.Result;
import com.imagent.server.common.UserContext;
import com.imagent.server.dto.AuthConfigVO;
import com.imagent.server.dto.AuthResponse;
import com.imagent.server.dto.CaptchaVO;
import com.imagent.server.dto.LoginRequest;
import com.imagent.server.dto.RegisterRequest;
import com.imagent.server.dto.ResetPasswordRequest;
import com.imagent.server.dto.SendEmailCodeRequest;
import com.imagent.server.dto.SendEmailCodeVO;
import com.imagent.server.dto.WsTicketVO;
import com.imagent.server.service.AuthSecurityService;
import com.imagent.server.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthSecurityService authSecurityService;

    @GetMapping("/config")
    public Result<AuthConfigVO> config() {
        return Result.ok(authSecurityService.getPublicAuthConfig());
    }

    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.ok(authSecurityService.generateCaptcha());
    }

    @PostMapping("/email-code")
    public Result<SendEmailCodeVO> sendEmailCode(@Valid @RequestBody SendEmailCodeRequest request,
                                                 HttpServletRequest httpRequest) {
        return Result.ok(authSecurityService.sendEmailVerificationCode(
                request.getEmail(),
                request.getCaptchaId(),
                request.getCaptchaCode(),
                extractClientIp(httpRequest)
        ));
    }

    @PostMapping("/register")
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                         HttpServletRequest httpRequest) {
        return Result.ok(userService.register(request, extractClientIp(httpRequest)));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                      HttpServletRequest httpRequest) {
        return Result.ok(userService.login(request, extractClientIp(httpRequest)));
    }

    @PostMapping("/password-reset/code")
    public Result<SendEmailCodeVO> sendPasswordResetCode(@Valid @RequestBody SendEmailCodeRequest request,
                                                         HttpServletRequest httpRequest) {
        return Result.ok(userService.sendPasswordResetCode(
                request.getEmail(),
                request.getCaptchaId(),
                request.getCaptchaCode(),
                extractClientIp(httpRequest)
        ));
    }

    @PostMapping("/password-reset/confirm")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
                                      HttpServletRequest httpRequest) {
        userService.resetPasswordByEmail(request, extractClientIp(httpRequest));
        return Result.ok();
    }

    /**
     * 为当前已认证用户签发 30 秒一次性 WebSocket 握手票据（该接口受 JwtAuthInterceptor 保护）
     */
    @PostMapping("/ws-ticket")
    public Result<WsTicketVO> issueWsTicket() {
        return Result.ok(authSecurityService.issueWsTicket(UserContext.getUserId()));
    }

    private String extractClientIp(HttpServletRequest request) {
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
