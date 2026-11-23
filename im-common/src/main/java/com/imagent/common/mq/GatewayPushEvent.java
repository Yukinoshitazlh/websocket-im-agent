package com.imagent.common.mq;

import com.imagent.common.protocol.WsPacket;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 跨节点推送事件：由 im-server 或 im-agent 发往 im.push.exchange，
 * 按 targetNodeId 精准路由到目标 im-gateway 节点推送给在线客户端。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayPushEvent {
    private String targetNodeId;
    private List<Long> targetUserIds;
    private WsPacket<?> packet;
}
