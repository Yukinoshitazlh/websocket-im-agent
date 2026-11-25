package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息撤回广播下行载荷（MSG_RECALL）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgRecallPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    private Long seq;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long operatorId;
    private String operatorNickname;
}
