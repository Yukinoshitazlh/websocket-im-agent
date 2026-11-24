package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateDirectConvRequest {
    @NotNull(message = "目标用户ID不能为空")
    private Long targetUserId;
}
