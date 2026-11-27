package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_friendship")
public class ImFriendship {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long friendId;
    /** 1 = 正常好友, 2 = 已拉黑 */
    private Integer status;
    private String remarkName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
