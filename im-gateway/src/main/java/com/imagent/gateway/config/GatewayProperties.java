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
}
