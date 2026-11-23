package com.imagent.agent.engine;

/**
 * AI Agent 工具抽象接口（阶段 3 手写 ReAct 调度循环的核心扩展点）
 */
public interface AgentTool {

    /** 提供给大模型的函数名称 */
    String name();

    /** 工具能力描述 */
    String description();

    /** 工具入参 JSON Schema */
    String parametersSchemaJson();

    /**
     * 是否需要人机协同审批（Human-in-the-Loop）：
     * - 读工具（如群聊历史检索）返回 false，直接执行；
     * - 写工具（如创建群提醒/发公告）返回 true，挂起任务并推送审批卡片。
     */
    default boolean requiresApproval() {
        return false;
    }

    /** 执行工具并返回给大模型的观测结果（Observation） */
    String execute(AgentToolContext context, String argumentsJson);
}
