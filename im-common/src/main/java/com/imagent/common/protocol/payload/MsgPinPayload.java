package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话置顶消息实时变更广播（MSG_PIN）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgPinPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    private Long seq;
    private String senderNickname;
    private String preview;
    private String pinnedByNickname;
    private Boolean pinned;
}
