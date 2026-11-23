package com.imagent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Agent 引擎强类型配置
 */
@Data
@ConfigurationProperties(prefix = "im.agent")
public class AgentProperties {
    private String llmBaseUrl = "https://api.deepseek.com/v1";
    private String llmApiKey = "";
    private String llmModel = "deepseek-chat";
    private int maxSteps = 5;
    private int stepTimeoutSeconds = 30;
    private String qdrantUrl = "http://127.0.0.1:6333";
}
