package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_user_presence_rule")
public class ImUserPresenceRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long targetUserId;
    private String ruleType;
    private LocalDateTime createdAt;
}
