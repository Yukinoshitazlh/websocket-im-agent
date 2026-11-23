package com.imagent.common.model;

import lombok.Getter;

/**
 * 统一业务与协议错误码
 */
@Getter
public enum ErrorCode {
    OK(200, "ok"),
    BAD_REQUEST(4000, "请求参数不合法"),
    UNAUTHORIZED(4010, "未登录或 Token 已失效"),
    FORBIDDEN(4030, "无权访问该会话或资源"),
    NOT_FOUND(4040, "资源不存在"),
    RATE_LIMITED(4290, "操作过于频繁，请稍后再试"),
    AGENT_QUOTA_EXCEEDED(4291, "今日 AI 助手额度已用完"),
    INTERNAL_ERROR(5000, "系统内部异常");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}
