package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class CreateGroupConvRequest {
    @NotBlank(message = "群名称不能为空")
    private String title;

    @NotEmpty(message = "群成员不能为空")
    private List<Long> memberIds;
}
