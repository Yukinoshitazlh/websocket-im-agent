-- ============================================================================
-- Flyway V6 迁移（v2.1 在线状态与隐身隐私控制）：
-- 1. im_user 新增 presence_mode（ALL=在线可见 / EXCEPT=不给谁看 / ONLY=部分可见 / NONE=全员隐身）
-- 2. 新增 im_user_presence_rule 持久化“不给谁看”与“部分可见”的定向好友名单（切换模式不丢失名单）
-- ============================================================================

ALTER TABLE im_user
    ADD COLUMN presence_mode VARCHAR(16) NOT NULL DEFAULT 'ALL' COMMENT '在线状态可见模式: ALL/EXCEPT/ONLY/NONE' AFTER status_text;

CREATE TABLE IF NOT EXISTS im_user_presence_rule (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id        BIGINT      NOT NULL COMMENT '设置人用户ID',
    target_user_id BIGINT      NOT NULL COMMENT '目标好友用户ID',
    rule_type      VARCHAR(16) NOT NULL COMMENT '规则类型: EXCEPT(不给谁看) / ONLY(部分可见)',
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_type_target (user_id, rule_type, target_user_id),
    KEY idx_user_type (user_id, rule_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户在线状态定向可见/隐身名单';
