package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 服务端消息落库回执载荷（SERVER_ACK）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerAckPayload {
    private String clientMsgId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private Long seq;
}
