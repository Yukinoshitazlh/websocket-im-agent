package com.imagent.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLlmConfigVO {
    private boolean enabled;
    private String baseUrl;
    private String model;
    private String apiKeyMask;
    private boolean hasApiKey;
}
