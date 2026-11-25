package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_file_object")
public class ImFileObject {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String sha256;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String storageType;
    private String objectKey;
    private String url;
    private Long uploaderId;
    private LocalDateTime createdAt;
}
