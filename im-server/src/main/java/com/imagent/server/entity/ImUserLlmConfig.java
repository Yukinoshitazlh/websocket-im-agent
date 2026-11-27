package com.imagent.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("im_user_llm_config")
public class ImUserLlmConfig {
    @TableId(type = IdType.INPUT)
    private Long userId;
    /** 0=未启用(走公共模型), 1=启用自带 Key(BYOK) */
    private Integer enabled;
    private String baseUrl;
    private String model;
    private String apiKeyEncrypted;
    private String apiKeyMask;
    private LocalDateTime updatedAt;
}
