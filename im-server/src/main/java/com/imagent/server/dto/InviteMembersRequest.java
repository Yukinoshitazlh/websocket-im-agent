package com.imagent.server.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class InviteMembersRequest {
    @NotEmpty(message = "请至少选择一名要邀请的成员")
    private List<Long> memberIds;
}
