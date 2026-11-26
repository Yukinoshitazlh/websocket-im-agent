package com.imagent.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Netty 网关强类型配置
 */
@Data
@ConfigurationProperties(prefix = "im.gateway")
public class GatewayProperties {
    private String nodeId = "gateway-1";
    private int wsPort = 9001;
    private String wsPath = "/ws";
    private int bossThreads = 1;
    private int workerThreads = 0;
    private int readerIdleSeconds = 60;
    private String jwtSecret = "im-agent-local-dev-jwt-secret-key-32bytes!";
    private long jwtExpireSeconds = 604800L;
    private String serverBaseUrl = "http://127.0.0.1:8080";
    /** 调用 im-server /internal/** 接口的共享密钥 */
    private String internalToken = "im-agent-local-dev-internal-token-32bytes!";
    /** 是否兼容 URL ?token= 长期 JWT 握手（开发/压测可开启，生产强制 false 仅允许 30s 一次性 ticket） */
    private boolean allowLegacyQueryToken = true;
}
