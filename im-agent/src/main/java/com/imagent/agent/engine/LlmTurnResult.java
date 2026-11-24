package com.imagent.agent.engine;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单轮大模型推理输出（可能是最终文本流，也可能是发起 Tool Call）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmTurnResult {
    private String text;
    private String toolCallId;
    private String toolCallName;
    private String toolCallArgumentsJson;
    private boolean aborted;
    private Integer ttftMs;
    private int promptTokens;
    private int completionTokens;

    public boolean hasToolCall() {
        return toolCallName != null && !toolCallName.isBlank();
    }
}
