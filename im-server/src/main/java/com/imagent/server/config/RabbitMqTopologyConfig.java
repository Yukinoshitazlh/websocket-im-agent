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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 声明 IM 推送、Agent 异步任务（含死信队列）及控制广播交换机拓扑
 */
@Configuration
public class RabbitMqTopologyConfig {

    /**
     * 专用于异步推送与 RabbitMQ 发布的平台线程池：
     * 规避 JDK 21 虚拟线程在 amqp-client 内部 synchronized 块上的载体线程固定（Pinning）问题，
     * 同时让 SERVER_ACK 无需等待下游 MQ 发布即可毫秒级返回。
     */
    @Bean("pushDispatchExecutor")
    public Executor pushDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("im-push-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

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
