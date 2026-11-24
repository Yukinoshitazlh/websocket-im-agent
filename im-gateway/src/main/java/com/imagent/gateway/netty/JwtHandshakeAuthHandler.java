package com.imagent.gateway.netty;

import com.imagent.common.security.JwtTokenUtil;
import com.imagent.gateway.config.GatewayProperties;
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
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * WebSocket 握手前 JWT 鉴权处理器
 * 校验失败直接拒绝 Upgrade，防止非法连接占用服务端资源。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class JwtHandshakeAuthHandler extends ChannelInboundHandlerAdapter {

    private final JwtTokenUtil jwtTokenUtil;
    private final GatewayProperties properties;

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

        List<String> tokens = decoder.parameters().get("token");
        if (tokens == null || tokens.isEmpty()) {
            sendUnauthorized(ctx, request, "Missing token");
            return;
        }

        try {
            Long userId = jwtTokenUtil.extractUserId(tokens.get(0));
            ctx.channel().attr(LocalChannelManager.ATTR_USER_ID).set(userId);
            // 剥离 query 参数，确保后续 WebSocketServerProtocolHandler 精确匹配 /ws
            request.setUri(properties.getWsPath());
            ctx.fireChannelRead(request);
            ctx.pipeline().remove(this);
        } catch (Exception e) {
            log.warn("WebSocket 握手鉴权失败: {}", e.getMessage());
            sendUnauthorized(ctx, request, "Invalid or expired token");
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
