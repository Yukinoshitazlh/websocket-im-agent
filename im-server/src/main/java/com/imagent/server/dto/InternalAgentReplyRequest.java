package com.imagent.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * im-agent 生成完毕（或打断截断）后回调 im-server 统一分配 seq 并持久化消息
 */
@Data
public class InternalAgentReplyRequest {
    @NotNull
    private Long conversationId;
    @NotNull
    private Long taskId;
    @NotBlank
    private String content;
    private String msgType = "AGENT_TEXT";
    private Long replyToMsgId;
}
