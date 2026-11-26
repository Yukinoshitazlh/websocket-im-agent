package com.imagent.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.imagent.common.model.BizException;
import com.imagent.common.model.ErrorCode;
import com.imagent.server.config.StorageProperties;
import com.imagent.server.dto.FileUploadVO;
import com.imagent.server.entity.ImFileObject;
import com.imagent.server.mapper.ImFileObjectMapper;
import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import io.minio.http.Method;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片/文件存储服务（v2.0 安全加固版）：
 * 1. 严格扩展名白名单 + 文件头魔数（Magic Bytes）校验，杜绝 .html/.svg/.js 等同源存储型 XSS 与伪装文件上传；
 * 2. 基于 SHA-256 唯一索引实现秒传；
 * 3. 支持 MinIO 私有桶预签名 URL 或独立域名，AUTO 模式下无 MinIO 时自动降级到本地安全下载端点。
 */
@Slf4j
@Service
public class FileStorageService {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 仅允许安全位图内联预览，严禁 SVG/HTML 等脚本格式 */
    public static final Set<String> SAFE_INLINE_IMAGE_EXTS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp"
    );

    /** 允许上传的文档/压缩包后缀白名单 */
    public static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp",
            ".pdf", ".txt", ".md", ".csv",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".zip"
    );

    private static final Map<String, String> SAFE_MIME_TYPES = Map.ofEntries(
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".png", "image/png"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".pdf", "application/pdf"),
            Map.entry(".txt", "text/plain; charset=UTF-8"),
            Map.entry(".md", "text/plain; charset=UTF-8"),
            Map.entry(".csv", "text/csv; charset=UTF-8"),
            Map.entry(".doc", "application/msword"),
            Map.entry(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry(".xls", "application/vnd.ms-excel"),
            Map.entry(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry(".ppt", "application/vnd.ms-powerpoint"),
            Map.entry(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry(".zip", "application/zip")
    );

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

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String ext = extractExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型（已拦截高危扩展名）: " + (ext.isEmpty() ? "未知" : ext));
        }

        byte[] bytes;
        String sha256;
        try {
            bytes = file.getBytes();
            validateMagicBytes(ext, bytes);
            sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (BizException e) {
            throw e;
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

        String contentType = SAFE_MIME_TYPES.getOrDefault(ext, "application/octet-stream");
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
                throw new BizException(ErrorCode.INTERNAL_ERROR, "MinIO 对象存储不可用，请确认服务已启动");
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

    /**
     * 安全解析本地文件路径（防止目录穿越，仅允许访问合法白名单文件）
     */
    public Path resolveLocalFile(String dateDir, String fileName) {
        if (dateDir == null || !dateDir.matches("\\d{8}")
                || fileName == null || !fileName.matches("[a-zA-Z0-9._-]+")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法文件路径");
        }
        String ext = extractExtension(fileName);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BizException(ErrorCode.FORBIDDEN, "禁止访问该类型文件");
        }
        Path root = Path.of(properties.getLocalDir()).toAbsolutePath().normalize();
        Path target = root.resolve(dateDir).resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            throw new BizException(ErrorCode.NOT_FOUND, "文件不存在");
        }
        return target;
    }

    public String resolveSafeContentType(String fileName) {
        return SAFE_MIME_TYPES.getOrDefault(extractExtension(fileName), "application/octet-stream");
    }

    public boolean isSafeInlineImage(String fileName) {
        return SAFE_INLINE_IMAGE_EXTS.contains(extractExtension(fileName));
    }

    /**
     * 文件头魔数校验，防止通过修改后缀名绕过白名单
     */
    void validateMagicBytes(String ext, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件内容为空");
        }
        boolean valid = switch (ext) {
            case ".jpg", ".jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
            case ".png" -> bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47
                    && bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A;
            case ".gif" -> bytes.length >= 6
                    && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
                    && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
            case ".webp" -> bytes.length >= 12
                    && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                    && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            case ".pdf" -> bytes.length >= 4
                    && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F';
            case ".zip", ".docx", ".xlsx", ".pptx" -> bytes.length >= 4
                    && bytes[0] == 'P' && bytes[1] == 'K'
                    && (bytes[2] == 0x03 || bytes[2] == 0x05 || bytes[2] == 0x07);
            case ".doc", ".xls", ".ppt" -> bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                    && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0;
            case ".txt", ".md", ".csv" -> !looksLikeHtmlScript(bytes);
            default -> false;
        };
        if (!valid) {
            throw new BizException(ErrorCode.BAD_REQUEST, "文件内容与扩展名不匹配或包含不安全内容");
        }
    }

    private boolean looksLikeHtmlScript(byte[] bytes) {
        int probeLen = Math.min(bytes.length, 512);
        String head = new String(bytes, 0, probeLen).trim().toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<script") || head.startsWith("<svg");
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
            return resolveMinioAccessUrl(objectKey);
        } catch (Exception e) {
            log.warn("MinIO 上传不可用，自动降级为本地文件存储: {}", e.getMessage());
            return null;
        }
    }

    private String resolveMinioAccessUrl(String objectKey) throws Exception {
        if (properties.isMinioPresignedEnabled()) {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getMinioBucket())
                    .object(objectKey)
                    .expiry(properties.getMinioPresignedExpirySeconds(), TimeUnit.SECONDS)
                    .build());
        }
        String base = (properties.getMinioPublicBaseUrl() != null && !properties.getMinioPublicBaseUrl().isBlank())
                ? properties.getMinioPublicBaseUrl()
                : properties.getMinioEndpoint();
        return base.replaceAll("/+$", "") + "/" + properties.getMinioBucket() + "/" + objectKey;
    }

    private void ensureBucketExists() throws Exception {
        if (bucketReady.get()) {
            return;
        }
        String bucket = properties.getMinioBucket();
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            // 仅在未开启预签名模式时配置只读策略；生产开启 presigned 时保持私有桶隔离
            if (!properties.isMinioPresignedEnabled()) {
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

    public String extractExtension(String fileName) {
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
        String accessUrl = obj.getUrl();
        if ("MINIO".equalsIgnoreCase(obj.getStorageType()) && properties.isMinioPresignedEnabled()) {
            try {
                accessUrl = resolveMinioAccessUrl(obj.getObjectKey());
            } catch (Exception e) {
                log.warn("生成 MinIO 预签名链接失败，回退原始 URL: key={}", obj.getObjectKey(), e);
            }
        }
        return FileUploadVO.builder()
                .id(obj.getId())
                .sha256(obj.getSha256())
                .fileName(obj.getFileName())
                .contentType(obj.getContentType())
                .fileSize(obj.getFileSize())
                .storageType(obj.getStorageType())
                .url(accessUrl)
                .instantHit(instantHit)
                .build();
    }
}
