package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class UpdatePresenceSettingsRequest {
    @NotBlank(message = "在线状态模式不能为空")
    private String mode;
    private List<Long> exceptUserIds;
    private List<Long> onlyUserIds;
}
