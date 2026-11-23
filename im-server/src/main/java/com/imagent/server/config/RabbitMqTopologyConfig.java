package com.imagent.server.config;

import com.imagent.common.constant.MqConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 声明 IM 推送、Agent 异步任务（含死信队列）及控制广播交换机拓扑
 */
@Configuration
public class RabbitMqTopologyConfig {

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange pushExchange() {
        return new DirectExchange(MqConstants.PUSH_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange agentExchange() {
        return new DirectExchange(MqConstants.AGENT_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange agentDeadLetterExchange() {
        return new DirectExchange(MqConstants.AGENT_DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue agentTaskQueue() {
        return QueueBuilder.durable(MqConstants.AGENT_TASK_QUEUE)
                .deadLetterExchange(MqConstants.AGENT_DLX_EXCHANGE)
                .deadLetterRoutingKey(MqConstants.AGENT_TASK_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue agentTaskDlq() {
        return QueueBuilder.durable(MqConstants.AGENT_TASK_DLQ).build();
    }

    @Bean
    public Binding agentTaskBinding() {
        return BindingBuilder.bind(agentTaskQueue())
                .to(agentExchange())
                .with(MqConstants.AGENT_TASK_ROUTING_KEY);
    }

    @Bean
    public Binding agentTaskDlqBinding() {
        return BindingBuilder.bind(agentTaskDlq())
                .to(agentDeadLetterExchange())
                .with(MqConstants.AGENT_TASK_DLQ_ROUTING_KEY);
    }

    @Bean
    public FanoutExchange agentControlExchange() {
        return new FanoutExchange(MqConstants.AGENT_CTRL_EXCHANGE, true, false);
    }
}
