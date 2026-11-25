package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 正在输入瞬时信令载荷（TYPING：不落库、不分配 seq、纯内存跨网关转发）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TypingPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private String nickname;
    private Boolean typing;
}
