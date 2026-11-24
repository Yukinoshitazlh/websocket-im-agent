package com.imagent.agent.engine;

import com.imagent.agent.mapper.ConversationReadMapper;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.mq.GatewayPushEvent;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.AgentApprovalCardPayload;
import com.imagent.common.protocol.payload.AgentStreamPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 下行推送服务：按 Redis 在线路由表将流式帧/审批卡片精准路由回各网关节点
 */
@Service
@RequiredArgsConstructor
public class AgentPushService {

    private final ConversationReadMapper conversationReadMapper;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    public void pushStream(Long conversationId, AgentStreamPayload payload) {
        pushPacketToConversation(conversationId, WsPacket.of(WsPacketType.AGENT_STREAM, payload));
    }

    public void pushApprovalCard(Long conversationId, AgentApprovalCardPayload payload) {
        pushPacketToConversation(conversationId, WsPacket.of(WsPacketType.AGENT_APPROVAL_CARD, payload));
    }

    private void pushPacketToConversation(Long conversationId, WsPacket<?> packet) {
        List<Long> memberIds = conversationReadMapper.selectMemberIds(conversationId);
        if (memberIds.isEmpty()) {
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
