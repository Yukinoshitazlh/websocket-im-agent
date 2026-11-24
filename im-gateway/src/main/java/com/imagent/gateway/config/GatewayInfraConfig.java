package com.imagent.gateway.config;

import com.imagent.common.constant.MqConstants;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 网关节点专属 RabbitMQ 推送队列与 Apache HttpClient 5 高并发连接池配置
 */
@Configuration
public class GatewayInfraConfig {

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange pushExchange() {
        return new DirectExchange(MqConstants.PUSH_EXCHANGE, true, false);
    }

    @Bean
    public Queue gatewayPushQueue(GatewayProperties properties) {
        return QueueBuilder.durable(MqConstants.gatewayPushQueue(properties.getNodeId())).build();
    }

    @Bean
    public Binding gatewayPushBinding(Queue gatewayPushQueue, DirectExchange pushExchange, GatewayProperties properties) {
        return BindingBuilder.bind(gatewayPushQueue)
                .to(pushExchange)
                .with(properties.getNodeId());
    }

    /**
     * 使用 Apache HttpClient 5 连接池（单路由 256 Keep-Alive 长连接），
     * 避免默认 HttpURLConnection 5 连接上限以及 JDK HttpClient 在虚拟线程突发下的流阻塞。
     */
    @Bean(destroyMethod = "close")
    public CloseableHttpClient internalHttpClient() {
        PoolingHttpClientConnectionManager connManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(256)
                .setMaxConnPerRoute(256)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .build();
    }

    @Bean
    public RestClient imServerRestClient(GatewayProperties properties, CloseableHttpClient internalHttpClient) {
        return RestClient.builder()
                .baseUrl(properties.getServerBaseUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(internalHttpClient))
                .build();
    }
}
