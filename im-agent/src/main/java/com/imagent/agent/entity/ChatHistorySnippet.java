package com.imagent.agent.entity;

import lombok.Data;

@Data
public class ChatHistorySnippet {
    private Long seq;
    private String senderNickname;
    private String content;
    private String msgType;
}
