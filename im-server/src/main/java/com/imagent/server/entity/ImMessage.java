package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_message")
public class ImMessage {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long senderId;
    private String clientMsgId;
    private Long seq;
    private String msgType;
    private String content;
    private Long replyToMsgId;
    private String extra;
    private Integer status;
    private LocalDateTime createdAt;
}
