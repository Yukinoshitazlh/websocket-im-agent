package com.imagent.server.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateUserLlmConfigRequest {
    private Boolean enabled;

    @Size(max = 255, message = "API Base URL 不能超过 255 个字符")
    private String baseUrl;

    @Size(max = 128, message = "模型名称不能超过 128 个字符")
    private String model;

    /** 留空表示沿用已保存的加密 API Key */
    @Size(max = 256, message = "API Key 长度不合法")
    private String apiKey;

    /** 为 true 时清空已保存的 API Key 并关闭 BYOK */
    private Boolean clearApiKey;
}
