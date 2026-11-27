package com.imagent.common.mq;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 异步 Agent 执行任务事件（im-server -> im.agent.task.queue -> im-agent）
 * 支持透传用户自带的大模型配置（BYOK）；字段为空时回退服务端公共模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTaskEvent {
    private Long taskId;
    private Long conversationId;
    private Long triggerUserId;
    private Long triggerMsgId;
    private String userPrompt;

    /** 可选：用户自带 LLM 接口配置（BYOK，仅经内网 MQ 内存透传，不落库明文） */
    private String customLlmBaseUrl;
    private String customLlmApiKey;
    private String customLlmModel;
}
