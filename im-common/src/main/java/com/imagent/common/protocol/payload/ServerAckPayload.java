package com.imagent.common.protocol.payload;

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
    private Long msgId;
    private Long conversationId;
    private Long seq;
}
