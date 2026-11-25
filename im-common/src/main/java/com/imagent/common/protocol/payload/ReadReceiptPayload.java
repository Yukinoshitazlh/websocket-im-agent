package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 实时已读水位回执推送（READ_RECEIPT）：零写放大，仅同步某成员最新 lastReadSeq
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptPayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long userId;
    private Long lastReadSeq;
}
