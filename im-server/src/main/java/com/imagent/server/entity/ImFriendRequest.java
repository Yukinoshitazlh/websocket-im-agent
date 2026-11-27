package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_friend_request")
public class ImFriendRequest {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private String remark;
    /** PENDING / ACCEPTED / REJECTED */
    private String status;
    private LocalDateTime handledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
