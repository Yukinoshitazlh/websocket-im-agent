-- ============================================================================
-- Flyway V3 迁移（v2.1 完整产品体验版）：
-- 1. 微信式好友申请表（im_friend_request）
-- 2. 双向好友关系与黑名单表（im_friendship），自动将老库既有单聊双方初始化为好友
-- 3. 用户自定义大模型 API Key（BYOK，AES-256-GCM 加密存储）表（im_user_llm_config）
-- ============================================================================

CREATE TABLE IF NOT EXISTS im_friend_request (
    id           BIGINT       NOT NULL COMMENT '申请单ID',
    from_user_id BIGINT       NOT NULL COMMENT '申请人用户ID',
    to_user_id   BIGINT       NOT NULL COMMENT '接收人用户ID',
    remark       VARCHAR(120) NOT NULL DEFAULT '' COMMENT '验证附言',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    handled_at   DATETIME(3)  DEFAULT NULL COMMENT '处理时间',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_to_status_time (to_user_id, status, created_at),
    KEY idx_from_to_status (from_user_id, to_user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友申请表';

CREATE TABLE IF NOT EXISTS im_friendship (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT      NOT NULL COMMENT '本人用户ID',
    friend_id   BIGINT      NOT NULL COMMENT '对方用户ID',
    status      TINYINT     NOT NULL DEFAULT 1 COMMENT '1=好友, 2=已拉黑',
    remark_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT '好友备注名',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_friend (user_id, friend_id),
    KEY idx_friend_status (friend_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='双向好友关系与黑名单表';

CREATE TABLE IF NOT EXISTS im_user_llm_config (
    user_id           BIGINT       NOT NULL COMMENT '用户ID',
    enabled           TINYINT      NOT NULL DEFAULT 0 COMMENT '是否启用自带 Key: 0=否, 1=是',
    base_url          VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'OpenAI 兼容接口 Base URL',
    model             VARCHAR(128) NOT NULL DEFAULT '' COMMENT '自定义模型名称',
    api_key_encrypted TEXT         DEFAULT NULL COMMENT 'AES-256-GCM 加密后的 API Key',
    api_key_mask      VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '脱敏展示 Key（如 sk-ab****9f21）',
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户自定义 AI 大模型配置（BYOK）';

-- 平滑迁移：将历史已存在单聊会话的双方自动初始化为互为好友，避免老账号升级后中断已有私聊
INSERT IGNORE INTO im_friendship (user_id, friend_id, status)
SELECT m1.user_id, m2.user_id, 1
FROM im_conversation c
JOIN im_conversation_member m1 ON m1.conversation_id = c.id
JOIN im_conversation_member m2 ON m2.conversation_id = c.id AND m1.user_id <> m2.user_id
WHERE c.type = 1;
