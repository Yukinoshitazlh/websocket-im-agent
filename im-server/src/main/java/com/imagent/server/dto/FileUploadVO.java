package com.imagent.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileUploadVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String sha256;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String storageType;
    private String url;
    private boolean instantHit;
}
