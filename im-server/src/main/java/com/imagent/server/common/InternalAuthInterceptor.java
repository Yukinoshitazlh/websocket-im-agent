package com.imagent.server.common;

import com.imagent.common.constant.InternalAuthConstants;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.ServerSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部服务调用鉴权拦截器（拦截 /internal/**，校验共享密钥 Header，防止公网越权冒充发送消息）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final ServerSecurityProperties securityProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = securityProperties.getInternalToken();
        String provided = request.getHeader(InternalAuthConstants.HEADER_INTERNAL_TOKEN);
        if (expected == null || expected.isBlank() || provided == null || provided.isBlank()) {
            log.warn("拒绝未携带内部鉴权令牌的请求: uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            throw new BizException(ErrorCode.FORBIDDEN, "禁止访问内部接口");
        }
        boolean matched = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8)
        );
        if (!matched) {
            log.warn("内部鉴权令牌校验失败: uri={}, remoteAddr={}", request.getRequestURI(), request.getRemoteAddr());
            throw new BizException(ErrorCode.FORBIDDEN, "内部鉴权失败");
        }
        return true;
    }
}
