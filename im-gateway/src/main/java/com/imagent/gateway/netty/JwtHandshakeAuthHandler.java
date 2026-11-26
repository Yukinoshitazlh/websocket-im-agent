package com.imagent.gateway.netty;

import com.imagent.common.constant.RedisKeys;
import com.imagent.common.security.JwtTokenUtil;
import com.imagent.gateway.config.GatewayProperties;
import io.jsonwebtoken.Claims;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WebSocket 握手前鉴权处理器（v2.0 加固版）：
 * 1. 优先校验 30 秒一次性短期票据 ?ticket=...，使用 Redis GETDEL 原子消费，彻底杜绝长期 JWT 泄露进网关/Nginx 访问日志；
 * 2. 仅在 allowLegacyQueryToken=true（本地开发/压测）时向后兼容 ?token=...，并同步校验 Redis TokenVersion 缓存。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class JwtHandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    private final JwtTokenUtil jwtTokenUtil;
    private final GatewayProperties properties;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        if (!properties.getWsPath().equals(decoder.path())) {
            sendUnauthorized(ctx, request, "Invalid WebSocket path");
            return;
        }

        Long userId = null;
        List<String> tickets = decoder.parameters().get("ticket");
        if (tickets != null && !tickets.isEmpty() && !tickets.get(0).isBlank()) {
            userId = consumeWsTicket(tickets.get(0).trim());
            if (userId == null) {
                log.warn("WebSocket 一次性票据无效或已过期");
                sendUnauthorized(ctx, request, "Invalid or expired ws ticket");
                return;
            }
        } else if (properties.isAllowLegacyQueryToken()) {
            List<String> tokens = decoder.parameters().get("token");
            if (tokens == null || tokens.isEmpty() || tokens.get(0).isBlank()) {
                sendUnauthorized(ctx, request, "Missing ticket or token");
                return;
            }
            userId = verifyLegacyJwt(tokens.get(0).trim());
            if (userId == null) {
                sendUnauthorized(ctx, request, "Invalid or expired token");
                return;
            }
        } else {
            sendUnauthorized(ctx, request, "Missing ws ticket");
            return;
        }

        ctx.channel().attr(LocalChannelManager.ATTR_USER_ID).set(userId);
        // 剥离 query 参数，确保后续 WebSocketServerProtocolHandler 精确匹配 /ws
        request.setUri(properties.getWsPath());
        ctx.fireChannelRead(request);
        ctx.pipeline().remove(this);
    }

    private Long consumeWsTicket(String ticket) {
        try {
            String rawUserId = redisTemplate.opsForValue().getAndDelete(RedisKeys.wsTicket(ticket));
            if (rawUserId == null || rawUserId.isBlank()) {
                return null;
            }
            return Long.valueOf(rawUserId);
        } catch (Exception e) {
            log.warn("校验一次性 WS Ticket 失败: {}", e.getMessage());
            return null;
        }
    }

    private Long verifyLegacyJwt(String token) {
        try {
            Claims claims = jwtTokenUtil.parseAndValidate(token);
            Long userId = Long.valueOf(claims.getSubject());
            int tokenVersion = jwtTokenUtil.extractTokenVersion(claims);

            String cachedState = redisTemplate.opsForValue().get(RedisKeys.userTokenVersion(userId));
            if (cachedState != null && cachedState.contains(":")) {
                String[] parts = cachedState.split(":", 2);
                int currentVersion = Integer.parseInt(parts[0]);
                int status = Integer.parseInt(parts[1]);
                if (status != 1 || tokenVersion != currentVersion) {
                    log.warn("拒绝已失效 Token 版本的 WS 握手: userId={}, tokenTv={}, currentTv={}, status={}",
                            userId, tokenVersion, currentVersion, status);
                    return null;
                }
            }
            return userId;
        } catch (Exception e) {
            log.warn("WebSocket JWT 握手鉴权失败: {}", e.getMessage());
            return null;
        }
    }

    private void sendUnauthorized(ChannelHandlerContext ctx, FullHttpRequest request, String reason) {
        request.release();
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                Unpooled.copiedBuffer(reason, StandardCharsets.UTF_8)
        );
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
