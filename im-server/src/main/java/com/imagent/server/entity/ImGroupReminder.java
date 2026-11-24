package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_group_reminder")
public class ImGroupReminder {
    @TableId(type = IdType.ASSIGN_ID)
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long creatorId;
    private String title;
    private String content;
    private LocalDateTime remindAt;
    private String status;
    private LocalDateTime createdAt;
}
