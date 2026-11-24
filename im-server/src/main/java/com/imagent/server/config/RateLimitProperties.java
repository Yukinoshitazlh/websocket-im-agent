package com.imagent.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 差异化限流强类型配置（普通消息高频放行，@AI 严格配额防刷爆账单）
 */
@Data
@ConfigurationProperties(prefix = "im.rate-limit")
public class RateLimitProperties {
    private boolean enabled = true;
    private int chatBucketCapacity = 30;
    private double chatRefillPerSecond = 8.0;
    private int agentBucketCapacity = 5;
    private double agentRefillPerSecond = 0.2;
    private int agentDailyQuota = 100;
}
