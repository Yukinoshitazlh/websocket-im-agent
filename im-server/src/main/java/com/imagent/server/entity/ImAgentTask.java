package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_agent_task")
public class ImAgentTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long triggerUserId;
    private Long triggerMsgId;
    private Long replyMsgId;
    private String status;
    private Integer currentStep;
    private String checkpointState;
    private Integer ttftMs;
    private Integer totalMs;
    private Integer promptTokens;
    private Integer completionTokens;
    private String errorMsg;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
