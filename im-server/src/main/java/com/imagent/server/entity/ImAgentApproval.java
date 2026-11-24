package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_agent_approval")
public class ImAgentApproval {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long conversationId;
    private Long requesterId;
    private Long approverId;
    private String toolName;
    private String toolArgs;
    private String summary;
    private String decision;
    private String comment;
    private LocalDateTime expireAt;
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
}
