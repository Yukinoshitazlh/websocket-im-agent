package com.imagent.server.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateConvPrefsRequest {
    private Boolean pinned;
    private Boolean muted;
    @Size(max = 64, message = "群备注不能超过 64 个字符")
    private String groupRemark;
    @Size(max = 64, message = "群昵称不能超过 64 个字符")
    private String groupNickname;
    private Boolean showMemberNickname;
}
