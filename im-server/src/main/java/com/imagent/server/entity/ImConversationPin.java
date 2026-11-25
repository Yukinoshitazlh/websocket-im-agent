package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_conversation_pin")
public class ImConversationPin {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long msgId;
    private Long pinnedBy;
    private LocalDateTime pinnedAt;
}
