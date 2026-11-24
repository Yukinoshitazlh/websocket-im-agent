package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_user")
public class ImUser {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String avatar;
    private Integer userType;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
