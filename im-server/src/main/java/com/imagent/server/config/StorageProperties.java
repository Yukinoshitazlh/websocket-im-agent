package com.imagent.server.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对象存储配置（支持 MINIO / LOCAL / AUTO 优先 MinIO 失败自动降级本地）
 */
@Data
@ConfigurationProperties(prefix = "im.storage")
public class StorageProperties {
    private String mode = "AUTO";
    private String localDir = "./uploads";
    private String minioEndpoint = "http://127.0.0.1:9100";
    /** 可选：对外访问的独立文件域名（如 https://files.example.com），避免与主站同源 */
    private String minioPublicBaseUrl = "";
    private String minioAccessKey = "im_minio_admin";
    private String minioSecretKey = "im_minio_123456";
    private String minioBucket = "im-files";
    /** 生产推荐开启：关闭桶公共读，使用 MinIO 短期预签名 URL 访问私聊文件 */
    private boolean minioPresignedEnabled = false;
    /** 预签名 URL 有效期（秒），默认 3600 秒 */
    private int minioPresignedExpirySeconds = 3600;
}
