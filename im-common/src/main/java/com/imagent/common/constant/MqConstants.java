package com.imagent.common.constant;

/**
 * RabbitMQ Exchange / Queue / RoutingKey 拓扑常量
 */
public final class MqConstants {

    private MqConstants() {
    }

    // 1. 跨网关节点下行推送拓扑（每个网关绑定专属队列，按 nodeId 精准路由）
    public static final String PUSH_EXCHANGE = "im.push.exchange";
    public static final String PUSH_QUEUE_PREFIX = "im.push.gateway.";

    public static String gatewayPushQueue(String nodeId) {
        return PUSH_QUEUE_PREFIX + nodeId;
    }

    // 2. Agent 异步任务拓扑（含死信队列防止毒消息阻塞）
    public static final String AGENT_EXCHANGE = "im.agent.exchange";
    public static final String AGENT_TASK_QUEUE = "im.agent.task.queue";
    public static final String AGENT_TASK_ROUTING_KEY = "agent.task";

    public static final String AGENT_DLX_EXCHANGE = "im.agent.dlx";
    public static final String AGENT_TASK_DLQ = "im.agent.task.dlq";
    public static final String AGENT_TASK_DLQ_ROUTING_KEY = "agent.task.dead";

    // 3. Agent 控制信号广播拓扑（用于实时打断 Cancel 与人机审批唤醒）
    public static final String AGENT_CTRL_EXCHANGE = "im.agent.ctrl.exchange";
}
