package com.imagent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_group_reminder")
public class GroupReminderEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long creatorId;
    private String title;
    private String content;
    private LocalDateTime remindAt;
    private String status;
    private LocalDateTime createdAt;
}
