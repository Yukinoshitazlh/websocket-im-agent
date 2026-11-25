package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenameGroupRequest {
    @NotBlank(message = "群名称不能为空")
    @Size(max = 64, message = "群名称不能超过 64 个字符")
    private String title;
}
