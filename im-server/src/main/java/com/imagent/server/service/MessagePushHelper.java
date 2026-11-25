package com.imagent.server.service;

import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.mq.GatewayPushEvent;
import com.imagent.common.protocol.WsPacket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 统一跨网关在线推送器：通过 Redis MGET 按网关节点聚合投递 RabbitMQ Direct Exchange，
 * 并卸载至专用平台线程池避免 JDK 21 虚拟线程在 amqp-client 同步锁上的载体固定（Carrier Pinning）。
 */
@Slf4j
@Component
public class MessagePushHelper {

    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final Executor pushDispatchExecutor;

    public MessagePushHelper(StringRedisTemplate redisTemplate,
                             RabbitTemplate rabbitTemplate,
                             @Qualifier("pushDispatchExecutor") Executor pushDispatchExecutor) {
        this.redisTemplate = redisTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.pushDispatchExecutor = pushDispatchExecutor;
    }

    public void pushAsync(List<Long> memberIds, WsPacket<?> packet) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        pushDispatchExecutor.execute(() -> {
            try {
                pushSync(memberIds, packet);
            } catch (Exception e) {
                log.error("异步跨网关推送失败: packetType={}", packet.getType(), e);
            }
        });
    }

    public void pushSync(List<Long> memberIds, WsPacket<?> packet) {
        if (memberIds == null || memberIds.isEmpty()) {
            return;
        }
        List<String> routeKeys = memberIds.stream().map(RedisKeys::userRoute).toList();
        List<String> nodeIds = redisTemplate.opsForValue().multiGet(routeKeys);
        if (nodeIds == null) {
            return;
        }

        Map<String, List<Long>> nodeToUsers = new HashMap<>();
        for (int i = 0; i < memberIds.size(); i++) {
            String nodeId = nodeIds.get(i);
            if (nodeId != null && !nodeId.isBlank()) {
                nodeToUsers.computeIfAbsent(nodeId, k -> new ArrayList<>()).add(memberIds.get(i));
            }
        }

        nodeToUsers.forEach((nodeId, userIds) -> {
            GatewayPushEvent event = GatewayPushEvent.builder()
                    .targetNodeId(nodeId)
                    .targetUserIds(userIds)
                    .packet(packet)
                    .build();
            rabbitTemplate.convertAndSend(MqConstants.PUSH_EXCHANGE, nodeId, event);
        });
    }
}
