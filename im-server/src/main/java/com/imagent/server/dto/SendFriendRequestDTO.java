package com.imagent.server.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendFriendRequestDTO {
    @NotNull(message = "目标用户 ID 不能为空")
    private Long targetUserId;

    @Size(max = 120, message = "验证附言不能超过 120 个字符")
    private String remark;
}
