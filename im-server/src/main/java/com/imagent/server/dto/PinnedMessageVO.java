package com.imagent.server.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinnedMessageVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long msgId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private Long seq;
    private String senderNickname;
    private String msgType;
    private String preview;
    private String pinnedByNickname;
    private Long pinnedAt;
}
