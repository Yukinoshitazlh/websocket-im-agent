package com.imagent.common.protocol.payload;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话元数据/成员变更实时广播（CONV_UPDATE：改群名、拉人、踢人、退群）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConvUpdatePayload {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long conversationId;
    private String eventType;
    private String title;
    private String operatorNickname;
}
