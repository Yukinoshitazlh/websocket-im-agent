package com.imagent.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresenceSettingsVO {
    private String mode;
    private List<String> exceptUserIds;
    private List<String> onlyUserIds;
}
