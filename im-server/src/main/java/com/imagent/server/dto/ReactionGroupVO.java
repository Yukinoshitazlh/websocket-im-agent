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
public class ReactionGroupVO {
    private String emoji;
    private Integer count;
    private Boolean reactedByMe;
    private List<String> users;
}
