package com.imagent.gateway.netty;

import com.imagent.gateway.config.GatewayProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty WebSocket 服务端优雅启停骨架（实现 SmartLifecycle 与 Spring 生命周期绑定）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NettyServerBootstrap implements SmartLifecycle {

    private final GatewayProperties properties;
    private final JwtHandshakeAuthHandler jwtHandshakeAuthHandler;
    private final WsFrameDispatchHandler wsFrameDispatchHandler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        bossGroup = new NioEventLoopGroup(properties.getBossThreads());
        workerGroup = properties.getWorkerThreads() > 0
                ? new NioEventLoopGroup(properties.getWorkerThreads())
                : new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(65536))
                                    .addLast(new IdleStateHandler(properties.getReaderIdleSeconds(), 0, 0, TimeUnit.SECONDS))
                                    .addLast(jwtHandshakeAuthHandler)
                                    .addLast(new WebSocketServerProtocolHandler(properties.getWsPath(), null, true, 65536))
                                    .addLast(wsFrameDispatchHandler);
                        }
                    });

            serverChannel = bootstrap.bind(properties.getWsPort()).sync().channel();
            log.info("Netty WebSocket 网关启动成功: nodeId={}, port={}, path={}",
                    properties.getNodeId(), properties.getWsPort(), properties.getWsPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("Netty 网关启动被中断", e);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("正在优雅关闭 Netty WebSocket 网关: nodeId={}", properties.getNodeId());
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
