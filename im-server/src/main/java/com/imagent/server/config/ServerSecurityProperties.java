package com.imagent.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "im.security")
public class ServerSecurityProperties {
    private String jwtSecret = "im-agent-local-dev-jwt-secret-key-32bytes!";
    private long jwtExpireSeconds = 604800L;
}
