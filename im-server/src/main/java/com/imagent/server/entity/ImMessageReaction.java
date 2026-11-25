package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_message_reaction")
public class ImMessageReaction {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long msgId;
    private Long conversationId;
    private Long userId;
    private String emoji;
    private LocalDateTime createdAt;
}
