package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_conversation")
public class ImConversation {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Integer type;
    private String bizKey;
    private String title;
    private Long ownerId;
    private Long currentSeq;
    private Long lastMsgId;
    private String lastMsgPreview;
    private LocalDateTime lastMsgTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
