package com.imagent.common.constant;

/**
 * 服务间内部调用鉴权常量
 */
public final class InternalAuthConstants {

    private InternalAuthConstants() {
    }

    /** 内部服务调用共享密钥请求头 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
}
