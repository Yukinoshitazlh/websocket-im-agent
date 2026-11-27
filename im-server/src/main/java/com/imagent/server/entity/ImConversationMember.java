package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_conversation_member")
public class ImConversationMember {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long conversationId;
    private Long userId;
    private Integer role;
    private Long lastReadSeq;
    private Integer muted;
    private Integer pinned;
    private String groupRemark;
    private String groupNickname;
    private Integer showMemberNickname;
    private LocalDateTime joinedAt;
}
