-- ============================================================================
-- Flyway V5 迁移（v2.1 会话右键菜单增强）：
-- 为 im_conversation_member 新增：
--   1. manual_unread：手动「标为未读」标记（不破坏对方看到的已读水位）
--   2. hidden：个人「删除该聊天」后的会话列表隐藏状态（来新消息或重新发单聊时自动恢复）
--   3. cleared_before_seq：个人已清空的历史消息水位（仅展示 seq > 该水位的消息，不影响他人）
-- ============================================================================

ALTER TABLE im_conversation_member
    ADD COLUMN manual_unread      TINYINT NOT NULL DEFAULT 0 COMMENT '手动标为未读标记: 0=否, 1=是' AFTER show_member_nickname,
    ADD COLUMN hidden             TINYINT NOT NULL DEFAULT 0 COMMENT '是否从个人会话列表隐藏: 0=否, 1=是' AFTER manual_unread,
    ADD COLUMN cleared_before_seq BIGINT  NOT NULL DEFAULT 0 COMMENT '个人清空历史消息水位(仅可见大于该seq的消息)' AFTER hidden;
