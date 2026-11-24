package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InternalAgentCancelRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long taskId;
    @NotNull
    private Long conversationId;
}
