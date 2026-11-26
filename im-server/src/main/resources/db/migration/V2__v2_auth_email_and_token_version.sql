-- ============================================================================
-- Flyway V2 迁移：支持邮箱注册/登录，增加 token_version 实现改密与封号立即失效
-- ============================================================================

ALTER TABLE im_user
    ADD COLUMN email VARCHAR(128) DEFAULT NULL COMMENT '绑定邮箱（唯一，支持邮箱注册与登录）' AFTER username,
    ADD COLUMN token_version INT NOT NULL DEFAULT 1 COMMENT 'JWT 令牌版本号，改密/封号时递增令旧令牌失效' AFTER status,
    ADD UNIQUE KEY uk_email (email);
