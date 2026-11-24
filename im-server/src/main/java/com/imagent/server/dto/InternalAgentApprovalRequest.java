package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class InternalAgentApprovalRequest {
    @NotNull
    private Long userId;
    @NotNull
    private Long taskId;
    @NotNull
    private Long approvalId;
    @NotNull
    private Boolean approved;
    private String comment;
}
