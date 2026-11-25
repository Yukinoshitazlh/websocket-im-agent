package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ToggleReactionRequest {
    @NotBlank(message = "表情不能为空")
    @Size(max = 16, message = "表情长度过长")
    private String emoji;
}
