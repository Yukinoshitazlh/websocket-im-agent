package com.imagent.common.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 统一信令包络
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsPacket<T> {
    private WsPacketType type;
    private String requestId;
    private Long timestamp;
    private T data;

    public static <T> WsPacket<T> of(WsPacketType type, String requestId, T data) {
        return WsPacket.<T>builder()
                .type(type)
                .requestId(requestId)
                .timestamp(System.currentTimeMillis())
                .data(data)
                .build();
    }

    public static <T> WsPacket<T> of(WsPacketType type, T data) {
        return of(type, null, data);
    }
}
