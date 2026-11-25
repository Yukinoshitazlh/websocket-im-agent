package com.imagent.server.dto;

import lombok.Data;

@Data
public class UpdateConvPrefsRequest {
    private Boolean pinned;
    private Boolean muted;
}
