package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InternalTypingRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long conversationId;
    private Boolean typing = true;
}
