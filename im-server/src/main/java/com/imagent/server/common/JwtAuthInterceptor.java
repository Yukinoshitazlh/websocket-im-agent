package com.imagent.server.common;

import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.security.JwtTokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * HTTP 接口 JWT 鉴权拦截器
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private final JwtTokenUtil jwtTokenUtil;

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
        try {
            Long userId = jwtTokenUtil.extractUserId(token);
            UserContext.setUserId(userId);
            return true;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "Token 无效或已过期");
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.clear();
    }
}
