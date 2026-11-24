package com.imagent.gateway.config;

import com.imagent.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 网关节点专属 RabbitMQ 推送队列与内部 HTTP 客户端配置
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

    @Bean
    public RestClient imServerRestClient(GatewayProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.getServerBaseUrl())
                .build();
    }
}
