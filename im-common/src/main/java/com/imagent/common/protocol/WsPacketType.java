package com.imagent.common.protocol;

/**
 * WebSocket 统一信令类型定义
 */
public enum WsPacketType {
    // 心跳
    PING,
    PONG,

    // 聊天收发与可靠性
    CHAT_SEND,
    SERVER_ACK,
    CHAT_PUSH,
    MSG_ACK,
    MSG_RECALL,
    TYPING,

    // AI Agent 流式交互、打断与人机协同
    AGENT_STREAM,
    AGENT_CANCEL,
    AGENT_APPROVAL_CARD,
    AGENT_APPROVAL,

    // 异常提示
    ERROR
}
