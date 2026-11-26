-- ============================================================================
-- Flyway V1 基线建表脚本（与 v1.0.0 保持 100% 一致，老库开启 baseline-on-migrate 自动平滑衔接）
-- ============================================================================

CREATE TABLE IF NOT EXISTS im_user (
    id            BIGINT       NOT NULL COMMENT '用户ID（雪花算法或业务生成）',
    username      VARCHAR(64)  NOT NULL COMMENT '唯一登录账号',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname      VARCHAR(64)  NOT NULL COMMENT '展示昵称',
    avatar        VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像 URL',
    signature     VARCHAR(120) NOT NULL DEFAULT '' COMMENT '个性签名',
    bio           VARCHAR(255) NOT NULL DEFAULT '' COMMENT '个人简介',
    status_emoji  VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '自定义状态图标',
    status_text   VARCHAR(32)  NOT NULL DEFAULT '' COMMENT '自定义状态文案',
    user_type     TINYINT      NOT NULL DEFAULT 1 COMMENT '1=普通用户, 2=AI Agent',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常, 0=禁用',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS im_conversation (
    id               BIGINT       NOT NULL COMMENT '会话ID',
    type             TINYINT      NOT NULL COMMENT '1=单聊, 2=群聊',
    biz_key          VARCHAR(128) DEFAULT NULL COMMENT '单聊防重复建会话唯一键，如 direct:minUid:maxUid',
    title            VARCHAR(128) NOT NULL DEFAULT '' COMMENT '群名称（单聊可为空）',
    owner_id         BIGINT       DEFAULT NULL COMMENT '群主ID',
    current_seq      BIGINT       NOT NULL DEFAULT 0 COMMENT '当前会话最大消息序号',
    last_msg_id      BIGINT       DEFAULT NULL COMMENT '最后一条消息ID',
    last_msg_preview VARCHAR(255) NOT NULL DEFAULT '' COMMENT '最后一条消息摘要',
    last_msg_time    DATETIME(3)  DEFAULT NULL COMMENT '最后一条消息时间',
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_last_msg_time (last_msg_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

CREATE TABLE IF NOT EXISTS im_conversation_member (
    id              BIGINT      NOT NULL COMMENT '主键ID',
    conversation_id BIGINT      NOT NULL COMMENT '会话ID',
    user_id         BIGINT      NOT NULL COMMENT '成员用户ID',
    role            TINYINT     NOT NULL DEFAULT 1 COMMENT '1=普通成员, 2=管理员, 3=群主, 4=AI Agent',
    last_read_seq   BIGINT      NOT NULL DEFAULT 0 COMMENT '该成员已读最大 seq',
    muted           TINYINT     NOT NULL DEFAULT 0 COMMENT '是否免打扰: 0=否, 1=是',
    pinned          TINYINT     NOT NULL DEFAULT 0 COMMENT '是否置顶会话: 0=否, 1=是',
    joined_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_user (conversation_id, user_id),
    KEY idx_user_conv (user_id, conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话成员表';

CREATE TABLE IF NOT EXISTS im_message (
    id              BIGINT       NOT NULL COMMENT '消息ID',
    conversation_id BIGINT       NOT NULL COMMENT '所属会话ID',
    sender_id       BIGINT       NOT NULL COMMENT '发送者ID',
    client_msg_id   VARCHAR(64)  NOT NULL COMMENT '客户端幂等唯一ID',
    seq             BIGINT       NOT NULL COMMENT '会话内单调递增序号',
    msg_type        VARCHAR(32)  NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/IMAGE/AGENT_TEXT/APPROVAL_CARD/SYSTEM',
    content         TEXT         NOT NULL COMMENT '消息正文',
    reply_to_msg_id BIGINT       DEFAULT NULL COMMENT '引用回复的目标消息ID',
    extra           JSON         DEFAULT NULL COMMENT '扩展元数据（@列表、工具调用轨迹、审批卡片参数等）',
    status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常, 2=已撤回',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_seq (conversation_id, seq),
    UNIQUE KEY uk_sender_client_msg (sender_id, client_msg_id),
    KEY idx_conv_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息表';

CREATE TABLE IF NOT EXISTS im_agent_task (
    id                BIGINT       NOT NULL COMMENT '任务ID',
    conversation_id   BIGINT       NOT NULL COMMENT '所属会话ID',
    trigger_user_id   BIGINT       NOT NULL COMMENT '触发用户ID',
    trigger_msg_id    BIGINT       NOT NULL COMMENT '触发消息ID',
    reply_msg_id      BIGINT       DEFAULT NULL COMMENT '最终落库的 Agent 回复消息ID',
    status            VARCHAR(32)  NOT NULL COMMENT 'QUEUED/RUNNING/WAITING_APPROVAL/DONE/ABORTED/FAILED',
    current_step      INT          NOT NULL DEFAULT 0 COMMENT '当前 ReAct 已执行步数',
    checkpoint_state  JSON         DEFAULT NULL COMMENT '挂起恢复时的对话上下文与待执行工具快照',
    ttft_ms           INT          DEFAULT NULL COMMENT '首字延迟 TTFT (ms)',
    total_ms          INT          DEFAULT NULL COMMENT '总执行耗时 (ms)',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT '输入 Token 数',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT '输出 Token 数',
    error_msg         VARCHAR(512) DEFAULT NULL COMMENT '异常摘要',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_conv_status (conversation_id, status),
    KEY idx_trigger_user_created (trigger_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent异步任务与状态机表';

CREATE TABLE IF NOT EXISTS im_agent_approval (
    id              BIGINT       NOT NULL COMMENT '审批单ID',
    task_id         BIGINT       NOT NULL COMMENT '关联 Agent 任务ID',
    conversation_id BIGINT       NOT NULL COMMENT '所属会话ID',
    requester_id    BIGINT       NOT NULL COMMENT '发起用户ID',
    approver_id     BIGINT       DEFAULT NULL COMMENT '实际审批人ID',
    tool_name       VARCHAR(64)  NOT NULL COMMENT '待执行工具名，如 create_group_reminder',
    tool_args       JSON         NOT NULL COMMENT '待执行工具入参 JSON',
    summary         VARCHAR(255) NOT NULL COMMENT '展示在审批卡片上的摘要说明',
    decision        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
    comment         VARCHAR(255) DEFAULT NULL COMMENT '审批备注',
    expire_at       DATETIME(3)  NOT NULL COMMENT '审批过期时间',
    decided_at      DATETIME(3)  DEFAULT NULL COMMENT '审批处理时间',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_task_id (task_id),
    KEY idx_conv_decision (conversation_id, decision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent人机协同审批表';

CREATE TABLE IF NOT EXISTS im_group_reminder (
    id              BIGINT       NOT NULL COMMENT '提醒ID',
    conversation_id BIGINT       NOT NULL COMMENT '目标会话ID',
    creator_id      BIGINT       NOT NULL COMMENT '发起人ID',
    title           VARCHAR(128) NOT NULL COMMENT '提醒标题',
    content         VARCHAR(512) NOT NULL COMMENT '提醒内容',
    remind_at       DATETIME(3)  NOT NULL COMMENT '计划触发时间',
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/CANCELED',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_status_remind_at (status, remind_at),
    KEY idx_conv_id (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群聊日程提醒表';

CREATE TABLE IF NOT EXISTS im_file_object (
    id           BIGINT       NOT NULL COMMENT '文件ID',
    sha256       CHAR(64)     NOT NULL COMMENT '文件内容 SHA-256 摘要（秒传唯一索引）',
    file_name    VARCHAR(255) NOT NULL COMMENT '原始文件名',
    content_type VARCHAR(128) NOT NULL DEFAULT 'application/octet-stream' COMMENT 'MIME 类型',
    file_size    BIGINT       NOT NULL COMMENT '文件字节大小',
    storage_type VARCHAR(16)  NOT NULL DEFAULT 'LOCAL' COMMENT 'MINIO / LOCAL',
    object_key   VARCHAR(255) NOT NULL COMMENT '存储对象键/相对路径',
    url          VARCHAR(512) NOT NULL COMMENT '前端可访问 URL',
    uploader_id  BIGINT       NOT NULL COMMENT '首次上传用户ID',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sha256 (sha256),
    KEY idx_uploader (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件对象与秒传元数据表';

CREATE TABLE IF NOT EXISTS im_message_reaction (
    id              BIGINT      NOT NULL COMMENT '主键ID',
    msg_id          BIGINT      NOT NULL COMMENT '消息ID',
    conversation_id BIGINT      NOT NULL COMMENT '会话ID',
    user_id         BIGINT      NOT NULL COMMENT '表态用户ID',
    emoji           VARCHAR(16) NOT NULL COMMENT '表情符号',
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_user_emoji (msg_id, user_id, emoji),
    KEY idx_conv_msg (conversation_id, msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息表情表态表';

CREATE TABLE IF NOT EXISTS im_conversation_pin (
    id              BIGINT      NOT NULL COMMENT '主键ID',
    conversation_id BIGINT      NOT NULL COMMENT '会话ID',
    msg_id          BIGINT      NOT NULL COMMENT '被置顶的消息ID',
    pinned_by       BIGINT      NOT NULL COMMENT '操作人ID',
    pinned_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_msg (conversation_id, msg_id),
    KEY idx_conv_time (conversation_id, pinned_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话置顶消息表';

INSERT IGNORE INTO im_user (id, username, password_hash, nickname, avatar, user_type, status)
VALUES (10000, 'ai_assistant', '$2a$10$disabled_agent_account_placeholder_do_not_login', 'AI 助手', '', 2, 1);
