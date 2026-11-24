package com.imagent.gateway.netty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.imagent.common.model.ErrorCode;
import com.imagent.common.model.Result;
import com.imagent.common.protocol.WsPacket;
import com.imagent.common.protocol.WsPacketType;
import com.imagent.common.protocol.payload.ChatSendPayload;
import com.imagent.common.protocol.payload.MsgAckPayload;
import com.imagent.common.protocol.payload.ServerAckPayload;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebSocket 文本帧业务分发处理器
 * 注意：绝不在 Netty I/O EventLoop 线程中执行同步阻塞 HTTP 调用，统一卸载到 JDK 21 虚拟线程池。
 */
@Slf4j
@Component
@ChannelHandler.Sharable
@RequiredArgsConstructor
public class WsFrameDispatchHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final LocalChannelManager channelManager;
    private final ObjectMapper objectMapper;
    private final RestClient imServerRestClient;

    private final ExecutorService businessExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            Long userId = ctx.channel().attr(LocalChannelManager.ATTR_USER_ID).get();
            if (userId != null) {
                channelManager.bind(userId, ctx.channel());
            } else {
                ctx.close();
            }
            return;
        }
        if (evt instanceof IdleStateEvent idleEvent && idleEvent.state() == IdleState.READER_IDLE) {
            Long userId = ctx.channel().attr(LocalChannelManager.ATTR_USER_ID).get();
            log.info("客户端读空闲超时，主动断开连接: userId={}, channel={}", userId, ctx.channel().id().asShortText());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelManager.unbind(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        Long userId = ctx.channel().attr(LocalChannelManager.ATTR_USER_ID).get();
        if (userId == null) {
            ctx.close();
            return;
        }

        try {
            WsPacket<JsonNode> packet = objectMapper.readValue(frame.text(), new TypeReference<>() {});
            if (packet.getType() == null) {
                writePacket(ctx.channel(), WsPacket.of(WsPacketType.ERROR, packet.getRequestId(),
                        Map.of("code", ErrorCode.BAD_REQUEST.getCode(), "message", "缺少信令 type")));
                return;
            }

            switch (packet.getType()) {
                case PING -> {
                    channelManager.renewRoute(userId);
                    writePacket(ctx.channel(), WsPacket.of(WsPacketType.PONG, packet.getRequestId(), Map.of()));
                }
                case CHAT_SEND -> {
                    ChatSendPayload payload = objectMapper.treeToValue(packet.getData(), ChatSendPayload.class);
                    handleChatSendAsync(ctx.channel(), userId, packet.getRequestId(), payload);
                }
                case MSG_ACK -> {
                    MsgAckPayload payload = objectMapper.treeToValue(packet.getData(), MsgAckPayload.class);
                    handleMsgAckAsync(userId, payload);
                }
                default -> writePacket(ctx.channel(), WsPacket.of(WsPacketType.ERROR, packet.getRequestId(),
                        Map.of("code", ErrorCode.BAD_REQUEST.getCode(), "message", "当前阶段暂不支持信令: " + packet.getType())));
            }
        } catch (Exception e) {
            log.warn("解析上行 WebSocket 帧失败: userId={}, err={}", userId, e.getMessage());
            writePacket(ctx.channel(), WsPacket.of(WsPacketType.ERROR, null,
                    Map.of("code", ErrorCode.BAD_REQUEST.getCode(), "message", "消息格式不合法")));
        }
    }

    private void handleChatSendAsync(Channel channel, Long senderId, String requestId, ChatSendPayload payload) {
        businessExecutor.execute(() -> {
            try {
                Result<ServerAckPayload> resp = imServerRestClient.post()
                        .uri("/internal/messages/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("senderId", senderId, "payload", payload))
                        .retrieve()
                        .body(new ParameterizedTypeReference<>() {});

                if (resp != null && resp.getCode() == 200 && resp.getData() != null) {
                    writePacket(channel, WsPacket.of(WsPacketType.SERVER_ACK, requestId, resp.getData()));
                } else {
                    String errMsg = resp != null ? resp.getMessage() : "消息服务异常";
                    int errCode = resp != null ? resp.getCode() : ErrorCode.INTERNAL_ERROR.getCode();
                    writePacket(channel, WsPacket.of(WsPacketType.ERROR, requestId,
                            Map.of("code", errCode, "message", errMsg)));
                }
            } catch (Exception e) {
                log.error("转发 CHAT_SEND 到 im-server 失败: senderId={}", senderId, e);
                writePacket(channel, WsPacket.of(WsPacketType.ERROR, requestId,
                        Map.of("code", ErrorCode.INTERNAL_ERROR.getCode(), "message", "消息投递失败，请稍后重试")));
            }
        });
    }

    private void handleMsgAckAsync(Long userId, MsgAckPayload payload) {
        if (payload == null || payload.getConversationId() == null || payload.getSeq() == null) {
            return;
        }
        businessExecutor.execute(() -> {
            try {
                imServerRestClient.post()
                        .uri("/internal/messages/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of(
                                "userId", userId,
                                "conversationId", payload.getConversationId(),
                                "seq", payload.getSeq()
                        ))
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("转发 MSG_ACK 到 im-server 失败: userId={}, convId={}, err={}",
                        userId, payload.getConversationId(), e.getMessage());
            }
        });
    }

    private void writePacket(Channel channel, WsPacket<?> packet) {
        if (!channel.isActive()) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(packet);
            channel.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            log.error("序列化下行 WebSocket 帧失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        businessExecutor.shutdown();
    }
}
