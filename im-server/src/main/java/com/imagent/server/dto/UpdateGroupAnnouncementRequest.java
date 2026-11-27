package com.imagent.server.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateGroupAnnouncementRequest {
    @Size(max = 1000, message = "群公告不能超过 1000 字")
    private String announcement;
}
