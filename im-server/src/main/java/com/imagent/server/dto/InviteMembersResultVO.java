package com.imagent.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InviteMembersResultVO {
    private int joinedCount;
    private int pendingApprovalCount;
    private String message;
}
