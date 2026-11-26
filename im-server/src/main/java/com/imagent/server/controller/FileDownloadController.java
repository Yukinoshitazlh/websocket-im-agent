package com.imagent.server.controller;

import com.imagent.server.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 本地文件安全下载/预览端点（替代裸静态资源托管）：
 * 强制添加 nosniff + sandbox CSP，仅白名单安全图片允许 inline，其他文件强制 attachment 下载。
 */
@RestController
@RequestMapping("/uploads")
@RequiredArgsConstructor
public class FileDownloadController {

    private final FileStorageService fileStorageService;

    @GetMapping("/{dateDir}/{fileName:.+}")
    public ResponseEntity<Resource> serveFile(@PathVariable String dateDir,
                                              @PathVariable String fileName) {
        Path filePath = fileStorageService.resolveLocalFile(dateDir, fileName);
        String contentType = fileStorageService.resolveSafeContentType(fileName);
        boolean inline = fileStorageService.isSafeInlineImage(fileName);

        ContentDisposition disposition = (inline
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(fileName, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(new FileSystemResource(filePath));
    }
}
