package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class AgentSummarizeRequest {
    @NotNull(message = "会话ID不能为空")
    private Long conversationId;
    /** UNREAD=未读省流总结, SELECTED=多选消息提炼 */
    @NotBlank(message = "总结模式不能为空")
    private String mode;
    private Long afterSeq;
    private List<Long> msgIds;
}
