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
    READ_RECEIPT,
    MSG_REACTION,
    MSG_PIN,
    CONV_UPDATE,

    // AI Agent 流式交互、打断与人机协同
    AGENT_STREAM,
    AGENT_CANCEL,
    AGENT_APPROVAL_CARD,
    AGENT_APPROVAL,

    // 好友申请与关系变更实时通知
    FRIEND_EVENT,

    // 安全会话控制（改密 / 封号强制踢下线）
    FORCE_LOGOUT,

    // 异常提示
    ERROR
}
