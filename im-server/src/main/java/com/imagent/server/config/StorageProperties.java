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
    private String minioAccessKey = "im_minio_admin";
    private String minioSecretKey = "im_minio_123456";
    private String minioBucket = "im-files";
}
