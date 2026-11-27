package com.imagent.server.config;

import com.imagent.server.common.InternalAuthInterceptor;
import com.imagent.server.common.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final InternalAuthInterceptor internalAuthInterceptor;
    private final ServerSecurityProperties securityProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 内部服务调用强鉴权（拦截 /internal/**）
        registry.addInterceptor(internalAuthInterceptor)
                .addPathPatterns("/internal/**");

        // 2. 用户 HTTP 接口 JWT + TokenVersion 鉴权（显式白名单放行公开接口，/api/auth/ws-ticket 需鉴权）
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/config",
                        "/api/auth/captcha",
                        "/api/auth/email-code",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/auth/password-reset/**",
                        "/api/health/**"
                );
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = securityProperties.resolveCorsAllowedOriginList();
        if (origins.isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "X-Requested-With")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
