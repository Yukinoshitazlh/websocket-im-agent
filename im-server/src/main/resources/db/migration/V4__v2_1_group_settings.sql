-- ============================================================================
-- Flyway V4 迁移（v2.1 群聊设置增强）：
-- 1. im_conversation 新增群公告与“进群需要群主/管理员确认”开关
-- 2. im_conversation_member 新增群备注（仅自己可见）、群内昵称、是否显示群成员昵称
-- 3. 新增 im_group_join_request 群聊入群审核申请表
-- ============================================================================

ALTER TABLE im_conversation
    ADD COLUMN announcement            TEXT         DEFAULT NULL COMMENT '群公告内容' AFTER title,
    ADD COLUMN announcement_updated_at DATETIME(3)  DEFAULT NULL COMMENT '群公告更新时间' AFTER announcement,
    ADD COLUMN announcement_updated_by BIGINT       DEFAULT NULL COMMENT '群公告发布人用户ID' AFTER announcement_updated_at,
    ADD COLUMN join_approval_required  TINYINT      NOT NULL DEFAULT 0 COMMENT '进群是否需要群主/群管理员确认: 0=否, 1=是' AFTER announcement_updated_by;

ALTER TABLE im_conversation_member
    ADD COLUMN group_remark         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '群聊备注（仅自己可见）' AFTER pinned,
    ADD COLUMN group_nickname       VARCHAR(64) NOT NULL DEFAULT '' COMMENT '我在本群的昵称' AFTER group_remark,
    ADD COLUMN show_member_nickname TINYINT     NOT NULL DEFAULT 1 COMMENT '是否显示群成员昵称: 1=是, 0=否' AFTER group_nickname;

CREATE TABLE IF NOT EXISTS im_group_join_request (
    id              BIGINT      NOT NULL COMMENT '申请ID',
    conversation_id BIGINT      NOT NULL COMMENT '群会话ID',
    inviter_id      BIGINT      NOT NULL COMMENT '邀请人用户ID',
    invitee_id      BIGINT      NOT NULL COMMENT '被邀请人用户ID',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reviewer_id     BIGINT      DEFAULT NULL COMMENT '审核人用户ID',
    reviewed_at     DATETIME(3) DEFAULT NULL COMMENT '审核时间',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv_status_time (conversation_id, status, created_at),
    KEY idx_conv_invitee_status (conversation_id, invitee_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊入群确认申请表';
