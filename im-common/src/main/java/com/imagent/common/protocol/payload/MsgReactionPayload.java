package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息表情表态实时广播（MSG_REACTION）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MsgReactionPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private String nickname;
    private String emoji;
    private Boolean added;
}
