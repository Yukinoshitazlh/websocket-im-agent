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
public class ConversationVO {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private Integer type;
    private String title;
    private String groupRemark;
    private String announcement;
    private Long announcementUpdatedAt;
    private String announcementPublisher;
    private Boolean joinApprovalRequired;
    private String myGroupNickname;
    private Boolean showMemberNickname;
    private Boolean stealthActive;
    private Integer pendingJoinRequestCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long ownerId;
    private Long currentSeq;
    private Long lastReadSeq;
    private Long unreadCount;
    private Long mentionSeq;
    private Boolean pinned;
    private Boolean muted;
    private Integer memberCount;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long peerUserId;
    private String peerAvatar;
    private Boolean peerOnline;
    private String peerStatusEmoji;
    private String peerStatusText;
    private String lastMsgPreview;
    private Long lastMsgTime;
}
