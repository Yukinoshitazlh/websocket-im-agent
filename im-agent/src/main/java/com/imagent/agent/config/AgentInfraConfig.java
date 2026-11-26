package com.imagent.agent.config;

import com.imagent.common.constant.InternalAuthConstants;
import com.imagent.common.constant.MqConstants;
import org.springframework.amqp.core.AnonymousQueue;
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
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Agent Worker 基础设施配置：MQ 拓扑（任务队列 + 死信队列 + 控制信号广播队列）与 WebClient
 */
@Configuration
public class AgentInfraConfig {

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

    /**
     * 每个 Worker 实例独占一个匿名控制队列，绑定 Fanout 交换机，
     * 保证多 Worker 扩容时“实时打断”和“审批唤醒”能瞬间广播到每个实例。
     */
    @Bean
    public Queue agentCtrlQueue() {
        return new AnonymousQueue();
    }

    @Bean
    public Binding agentCtrlBinding(Queue agentCtrlQueue, FanoutExchange agentControlExchange) {
        return BindingBuilder.bind(agentCtrlQueue).to(agentControlExchange);
    }

    @Bean
    public WebClient llmWebClient() {
        return WebClient.builder().build();
    }

    @Bean
    public WebClient imServerWebClient(AgentProperties properties) {
        return WebClient.builder()
                .baseUrl(properties.getServerBaseUrl())
                .defaultHeader(InternalAuthConstants.HEADER_INTERNAL_TOKEN, properties.getInternalToken())
                .build();
    }
}
