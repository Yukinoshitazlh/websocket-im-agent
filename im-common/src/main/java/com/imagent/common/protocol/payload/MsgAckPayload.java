package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 客户端已读回执上行载荷（MSG_ACK）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgAckPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private Long seq;
}
