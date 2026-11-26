package com.imagent.gateway.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.common.constant.RedisKeys;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.gateway.config.GatewayProperties;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 本地长连接与 Redis 在线路由表管理器
 * 关键设计：
 * 1. 下线清理时比对 Channel 引用，防止“新连接顶号上线后，旧连接关闭回调误删 Redis 路由”；
 * 2. 同账号重复建连顶号时，主动向旧连接推送 FORCE_LOGOUT 并以 4001 关闭码断开，彻底避免双页死循环互踢；
 * 3. 支持改密/异地登录强制踢下线与优雅停机全量排空连接及路由清理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalChannelManager {

    public static final AttributeKey<Long> ATTR_USER_ID = AttributeKey.valueOf("im.userId");
    public static final int WS_CLOSE_KICKED = 4001;
    private static final long ROUTE_TTL_SECONDS = 90L;

    private final ConcurrentHashMap<Long, Channel> userChannelMap = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public void bind(Long userId, Channel channel) {
        Channel previous = userChannelMap.put(userId, channel);
        if (previous != null && previous != channel && previous.isActive()) {
            log.info("用户 {} 在其他页面/设备建立新连接，踢出旧连接: oldChannel={}", userId, previous.id().asShortText());
            sendKickAndClose(previous, buildDuplicateKickFrame());
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
            deleteRouteIfOwnedBySelf(userId);
            log.info("用户下线解绑: userId={}, channel={}", userId, channel.id().asShortText());
        }
    }

    public void pushToUser(Long userId, String jsonFrame) {
        Channel channel = userChannelMap.get(userId);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(jsonFrame));
        }
    }

    /**
     * 强制下线：下发通知帧并以 4001 关闭码立即断开旧连接（用于重复登录/改密/封禁）
     */
    public void forceDisconnect(Long userId, String jsonFrame) {
        Channel channel = userChannelMap.remove(userId);
        deleteRouteIfOwnedBySelf(userId);
        if (channel != null && channel.isActive()) {
            log.info("强制踢下线在线连接: userId={}, channel={}", userId, channel.id().asShortText());
            sendKickAndClose(channel, jsonFrame);
        }
    }

    /**
     * 优雅停机排空：向本节点全部活跃连接发送 Going Away 关闭帧，并清理本节点对应的 Redis 路由，
     * 促发客户端立即重连到健康网关节点。
     */
    public void drainAllConnections() {
        List<Map.Entry<Long, Channel>> snapshot = new ArrayList<>(userChannelMap.entrySet());
        if (snapshot.isEmpty()) {
            return;
        }
        log.info("开始优雅排空本网关节点活跃连接: nodeId={}, activeCount={}", properties.getNodeId(), snapshot.size());
        for (Map.Entry<Long, Channel> entry : snapshot) {
            Long userId = entry.getKey();
            Channel ch = entry.getValue();
            userChannelMap.remove(userId, ch);
            deleteRouteIfOwnedBySelf(userId);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new CloseWebSocketFrame(1001, "Gateway node restarting"))
                        .addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private void sendKickAndClose(Channel channel, String jsonFrame) {
        if (jsonFrame != null) {
            channel.write(new TextWebSocketFrame(jsonFrame));
        }
        channel.writeAndFlush(new CloseWebSocketFrame(WS_CLOSE_KICKED, "Kicked by newer login"))
                .addListener(ChannelFutureListener.CLOSE);
    }

    private String buildDuplicateKickFrame() {
        try {
            WsPacket<Map<String, Object>> pkt = WsPacket.of(
                    WsPacketType.FORCE_LOGOUT,
                    Map.of("reason", "LOGIN_ELSEWHERE", "message", "你的账号已在其他设备或页面登录，当前会话已下线")
            );
            return objectMapper.writeValueAsString(pkt);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteRouteIfOwnedBySelf(Long userId) {
        try {
            String routeKey = RedisKeys.userRoute(userId);
            String currentRoute = redisTemplate.opsForValue().get(routeKey);
            if (properties.getNodeId().equals(currentRoute)) {
                redisTemplate.delete(routeKey);
            }
        } catch (Exception e) {
            log.warn("清理用户 Redis 路由失败: userId={}, err={}", userId, e.getMessage());
        }
    }
}
