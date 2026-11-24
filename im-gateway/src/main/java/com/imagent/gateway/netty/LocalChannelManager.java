package com.imagent.gateway.netty;

import com.imagent.common.constant.RedisKeys;
import com.imagent.gateway.config.GatewayProperties;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 本地长连接与 Redis 在线路由表管理器
 * 关键设计：下线清理时比对 Channel 引用，防止“新连接顶号上线后，旧连接关闭回调误删 Redis 路由”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalChannelManager {

    public static final AttributeKey<Long> ATTR_USER_ID = AttributeKey.valueOf("im.userId");
    private static final long ROUTE_TTL_SECONDS = 90L;

    private final ConcurrentHashMap<Long, Channel> userChannelMap = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;

    public void bind(Long userId, Channel channel) {
        Channel previous = userChannelMap.put(userId, channel);
        if (previous != null && previous != channel && previous.isActive()) {
            log.info("用户 {} 在其他端重连，关闭旧连接: oldChannel={}", userId, previous.id().asShortText());
            previous.close();
        }
        redisTemplate.opsForValue().set(
                RedisKeys.userRoute(userId),
                properties.getNodeId(),
                ROUTE_TTL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("用户上线绑定成功: userId={}, nodeId={}, channel={}",
                userId, properties.getNodeId(), channel.id().asShortText());
    }

    public void renewRoute(Long userId) {
        if (userId == null) {
            return;
        }
        redisTemplate.opsForValue().set(
                RedisKeys.userRoute(userId),
                properties.getNodeId(),
                ROUTE_TTL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    public void unbind(Channel channel) {
        Long userId = channel.attr(ATTR_USER_ID).get();
        if (userId == null) {
            return;
        }
        boolean removed = userChannelMap.remove(userId, channel);
        if (removed) {
            String routeKey = RedisKeys.userRoute(userId);
            String currentRoute = redisTemplate.opsForValue().get(routeKey);
            if (properties.getNodeId().equals(currentRoute)) {
                redisTemplate.delete(routeKey);
            }
            log.info("用户下线解绑: userId={}, channel={}", userId, channel.id().asShortText());
        }
    }

    public void pushToUser(Long userId, String jsonFrame) {
        Channel channel = userChannelMap.get(userId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(jsonFrame));
        }
    }
}
