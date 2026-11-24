package com.imagent.agent.mq;

import com.imagent.agent.engine.ActiveTaskRegistry;
import com.imagent.agent.engine.AgentOrchestrator;
import com.imagent.common.constant.MqConstants;
import com.imagent.common.mq.AgentControlEvent;
import com.imagent.common.mq.AgentTaskEvent;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Agent Worker RabbitMQ 消费者：
 * - 任务队列：提交到 JDK 21 虚拟线程池并发执行，避免单个慢推理阻塞其他会话的 @AI 任务
 * - 控制广播队列：毫秒级响应 CANCEL 打断与 RESUME_APPROVED/REJECTED 唤醒
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMqConsumers {

    private final AgentOrchestrator agentOrchestrator;
    private final ActiveTaskRegistry activeTaskRegistry;
    private final ExecutorService workerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @RabbitListener(queues = MqConstants.AGENT_TASK_QUEUE)
    public void onAgentTask(AgentTaskEvent event) {
        if (event == null || event.getTaskId() == null) {
            return;
        }
        log.info("收到异步 Agent 任务: taskId={}, convId={}", event.getTaskId(), event.getConversationId());
        workerExecutor.execute(() -> agentOrchestrator.executeTask(event));
    }

    @RabbitListener(queues = "#{agentCtrlQueue.name}")
    public void onAgentControl(AgentControlEvent event) {
        if (event == null || event.getTaskId() == null || event.getControlType() == null) {
            return;
        }
        switch (event.getControlType()) {
            case "CANCEL" -> activeTaskRegistry.cancel(event.getTaskId());
            case "RESUME_APPROVED", "RESUME_REJECTED" ->
                    workerExecutor.execute(() -> agentOrchestrator.resumeAfterApproval(event));
            default -> log.warn("未知 Agent 控制信号: {}", event.getControlType());
        }
    }

    @PreDestroy
    public void shutdown() {
        workerExecutor.shutdown();
    }
}
