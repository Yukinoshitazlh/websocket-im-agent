package com.imagent.gateway.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.common.mq.GatewayPushEvent;
import com.imagent.gateway.netty.LocalChannelManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 消费本网关节点专属推送队列，将消息写入在线用户 Channel
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayPushConsumer {

    private final LocalChannelManager channelManager;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = "#{gatewayPushQueue.name}")
    public void onPushEvent(GatewayPushEvent event) {
        if (event == null || event.getTargetUserIds() == null || event.getPacket() == null) {
            return;
        }
        try {
            String jsonFrame = objectMapper.writeValueAsString(event.getPacket());
            for (Long userId : event.getTargetUserIds()) {
                channelManager.pushToUser(userId, jsonFrame);
            }
        } catch (Exception e) {
            log.error("处理下行推送事件失败: event={}", event, e);
        }
    }
}
