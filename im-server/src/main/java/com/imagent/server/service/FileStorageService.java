package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.StorageProperties;
import com.imagent.server.dto.FileUploadVO;
import com.imagent.server.entity.ImFileObject;
import com.imagent.server.mapper.ImFileObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片/文件存储服务：
 * 1. 基于 SHA-256 唯一索引实现秒传（前端先调 instant-check，命中直接零流量返回 URL）；
 * 2. 优先上传至 MinIO 对象存储（自动建桶并配置公共只读策略）；
 * 3. AUTO 模式下若本地未启动 MinIO 容器，自动无缝降级到本地 uploads/ 目录静态托管。
 */
@Slf4j
@Service
public class FileStorageService {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ImFileObjectMapper fileObjectMapper;
    private final StorageProperties properties;
    private final MinioClient minioClient;
    private final AtomicBoolean bucketReady = new AtomicBoolean(false);

    public FileStorageService(ImFileObjectMapper fileObjectMapper, StorageProperties properties) {
        this.fileObjectMapper = fileObjectMapper;
        this.properties = properties;
        this.minioClient = MinioClient.builder()
                .endpoint(properties.getMinioEndpoint())
                .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey())
                .build();
    }

    public FileUploadVO checkInstantUpload(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            return null;
        }
        ImFileObject existing = fileObjectMapper.selectOne(new LambdaQueryWrapper<ImFileObject>()
                .eq(ImFileObject::getSha256, sha256.toLowerCase()));
        return existing == null ? null : toVO(existing, true);
    }

    public FileUploadVO upload(Long uploaderId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单个文件大小不能超过 20MB");
        }

        byte[] bytes;
        String sha256;
        try {
            bytes = file.getBytes();
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取文件或计算 SHA-256 失败");
        }

        // 1. 服务端 SHA-256 秒传去重
        ImFileObject existing = fileObjectMapper.selectOne(new LambdaQueryWrapper<ImFileObject>()
                .eq(ImFileObject::getSha256, sha256));
        if (existing != null) {
            log.info("文件上传命中 SHA-256 秒传: sha256={}, fileName={}", sha256, existing.getFileName());
            return toVO(existing, true);
        }

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String contentType = (file.getContentType() == null || file.getContentType().isBlank())
                ? "application/octet-stream" : file.getContentType();
        String ext = extractExtension(originalName);
        String objectKey = LocalDate.now().format(DATE_FMT) + "/" + UUID.randomUUID().toString().replace("-", "") + ext;

        String storageType;
        String url;
        if ("LOCAL".equalsIgnoreCase(properties.getMode())) {
            storageType = "LOCAL";
            url = saveLocally(objectKey, bytes);
        } else {
            String minioUrl = tryUploadMinio(objectKey, bytes, contentType);
            if (minioUrl != null) {
                storageType = "MINIO";
                url = minioUrl;
            } else if ("MINIO".equalsIgnoreCase(properties.getMode())) {
                throw new BizException(ErrorCode.INTERNAL_ERROR, "MinIO 对象存储不可用，请确认容器已启动");
            } else {
                storageType = "LOCAL";
                url = saveLocally(objectKey, bytes);
            }
        }

        ImFileObject entity = new ImFileObject();
        entity.setSha256(sha256);
        entity.setFileName(originalName);
        entity.setContentType(contentType);
        entity.setFileSize((long) bytes.length);
        entity.setStorageType(storageType);
        entity.setObjectKey(objectKey);
        entity.setUrl(url);
        entity.setUploaderId(uploaderId);

        try {
            fileObjectMapper.insert(entity);
            return toVO(entity, false);
        } catch (DuplicateKeyException e) {
            ImFileObject raceExisting = fileObjectMapper.selectOne(new LambdaQueryWrapper<ImFileObject>()
                    .eq(ImFileObject::getSha256, sha256));
            return raceExisting != null ? toVO(raceExisting, true) : toVO(entity, false);
        }
    }

    private String tryUploadMinio(String objectKey, byte[] bytes, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getMinioBucket())
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());
            return properties.getMinioEndpoint().replaceAll("/+$", "")
                    + "/" + properties.getMinioBucket() + "/" + objectKey;
        } catch (Exception e) {
            log.warn("MinIO 上传不可用，自动降级为本地文件存储: {}", e.getMessage());
            return null;
        }
    }

    private void ensureBucketExists() throws Exception {
        if (bucketReady.get()) {
            return;
        }
        String bucket = properties.getMinioBucket();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            String publicReadPolicy = """
                    {
                      "Version": "2012-10-17",
                      "Statement": [{
                        "Effect": "Allow",
                        "Principal": {"AWS": ["*"]},
                        "Action": ["s3:GetObject"],
                        "Resource": ["arn:aws:s3:::%s/*"]
                      }]
                    }
                    """.formatted(bucket);
            minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                    .bucket(bucket)
                    .config(publicReadPolicy)
                    .build());
        }
        bucketReady.set(true);
    }

    private String saveLocally(String objectKey, byte[] bytes) {
        try {
            Path target = Path.of(properties.getLocalDir()).toAbsolutePath().normalize().resolve(objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            return "/uploads/" + objectKey;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "写入本地文件失败: " + e.getMessage());
        }
    }

    private String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "file_" + System.currentTimeMillis();
        }
        String name = Path.of(raw).getFileName().toString();
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String extractExtension(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx > 0 && idx < fileName.length() - 1) {
            String ext = fileName.substring(idx).toLowerCase();
            if (ext.length() <= 10 && ext.matches("\\.[a-z0-9]+")) {
                return ext;
            }
        }
        return "";
    }

    private FileUploadVO toVO(ImFileObject obj, boolean instantHit) {
        return FileUploadVO.builder()
                .id(obj.getId())
                .sha256(obj.getSha256())
                .fileName(obj.getFileName())
                .contentType(obj.getContentType())
                .fileSize(obj.getFileSize())
                .storageType(obj.getStorageType())
                .url(obj.getUrl())
                .instantHit(instantHit)
                .build();
    }
}
