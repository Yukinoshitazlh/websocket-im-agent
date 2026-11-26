package com.imagent.server.common;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.service.TokenVersionService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP 接口 JWT + TokenVersion 鉴权拦截器
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenUtil jwtTokenUtil;
    private final TokenVersionService tokenVersionService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "缺少有效的 Authorization Token");
        }
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        Long userId;
        int tokenVersion;
        try {
            Claims claims = jwtTokenUtil.parseAndValidate(token);
            userId = Long.valueOf(claims.getSubject());
            tokenVersion = jwtTokenUtil.extractTokenVersion(claims);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "Token 无效或已过期");
        }

        tokenVersionService.assertValidToken(userId, tokenVersion);
        UserContext.setUserId(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
