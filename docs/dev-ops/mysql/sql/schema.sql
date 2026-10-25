-- ============================================================================
-- columbo 全量建表脚本（MySQL 5.7+/8.0）
-- 适用：从 H2 文件库迁移到 MySQL 时，直接整体执行本文件即可。
--
-- 使用方式（二选一）：
--   A. 提前手动执行：mysql -u<user> -p < docs/dev-ops/mysql/sql/schema.sql
--      应用启动时 CREATE TABLE IF NOT EXISTS 全部 no-op，直接开始读写。
--   B. 不手动执行：应用首次写入会自愈建库建表（代码内 DDL 与本文件保持一致）。
--
-- 数据源切换（application-dev.yml）：
--   ai.agent.session-store.url: jdbc:mysql://<host>:<port>/agent_session?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
--   ai.agent.session-store.username / password 按环境填写
--
-- 种子账号：sys_user 表为空时，应用首次登录会按 auth.seed 自动初始化（默认 admin/admin123，见 application-dev.yml）。
-- 加账号：INSERT INTO sys_user(username, name, password_hash) VALUES ('工号', '姓名', '<BCrypt哈希>')，
--   BCrypt 哈希可用任意语言的 bcrypt 库生成（应用内为 spring-security-crypto 的 BCryptPasswordEncoder）。
-- ============================================================================

CREATE DATABASE IF NOT EXISTS agent_session DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE agent_session;

-- ----------------------------------------------------------------------------
-- 1. 用户表（登录账号）：工号唯一，密码仅存 BCrypt 哈希；逻辑删除不参与登录与注册查重
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '工号(登录名,唯一)',
    name          VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '姓名',
    password_hash VARCHAR(128)     NOT NULL DEFAULT '' COMMENT 'BCrypt密码哈希',
    is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-否,1-是',
    create_by     VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '创建人',
    modified_by   VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '修改人',
    gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_gmt_create (gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(登录账号)';

-- ----------------------------------------------------------------------------
-- 2. 会话登记表：uk_app_session 幂等（重复登记只翻新时间）；
--    title/space 空串=默认语义（标题取首条用户消息 / 未归档）；删除为逻辑删除
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_name      VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '应用名(agent装配appName)',
    session_id    VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '会话ID(UUID)',
    user_id       VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '归属用户(工号)',
    title         VARCHAR(512)     NOT NULL DEFAULT '' COMMENT '自定义标题(空=取首条用户消息)',
    space         VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '归档空间(空=未归档)',
    is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-否,1-是',
    create_by     VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '创建人(=user_id)',
    modified_by   VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '修改人(重命名/移动/删除操作者)',
    gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_session (app_name, session_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话登记表';

-- ----------------------------------------------------------------------------
-- 3. 会话事件表：append-only（只增不改），author 即创建人故无 create_by/modified_by；
--    每个事件完整 JSON 快照，重启后按 id 顺序重放恢复会话；is_deleted 随会话软删
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session_event (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_name      VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '应用名',
    session_id    VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '会话ID',
    event_id      VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '事件ID(幂等去重键)',
    author        VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '事件作者(user/model),append-only表本列即创建人',
    event_json    MEDIUMTEXT       NOT NULL COMMENT '事件JSON原文',
    is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-否,1-是(随会话软删)',
    gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event (app_name, session_id, event_id),
    KEY idx_session_order (app_name, session_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话事件表(append-only,只增不改)';

-- ----------------------------------------------------------------------------
-- 4. 登录 token 存储（SaTokenDao 的 JDBC 实现）：服务重启后 token 依然有效，
--    7 天有效期真正落地；过期由应用惰性清理 + 启动时批量删除
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS satoken_store (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    store_key    VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '键(含sa-token前缀,如 satoken:login:token:xxx)',
    store_value  VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT '值(loginId等字符串)',
    expire_at    BIGINT          NOT NULL DEFAULT 0 COMMENT '过期毫秒时间戳,0=永不过期',
    gmt_create   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_store_key (store_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录token存储(SaTokenDao JDBC实现)';

-- ----------------------------------------------------------------------------
-- 5. 会话 token 用量表：按会话累计的模型调用成本记账（列累加，非覆盖）；
--    cached_tokens 是提示缓存命中部分（计入 input_tokens），真实计费输入 = 输入 - 缓存；
--    会话删除时随会话物理清理（派生数据，无审计价值）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_session_token (
    id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
    app_name      VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '应用名',
    session_id    VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '会话ID',
    input_tokens  BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计输入token(含缓存命中)',
    output_tokens BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计输出token',
    cached_tokens BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '其中提示缓存命中token(计入输入)',
    total_tokens  BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计总token',
    llm_calls     BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '模型调用次数',
    gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    gmt_modified  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_session (app_name, session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话token用量表(按会话累计)';

-- ----------------------------------------------------------------------------
-- 手动加账号示例（改密/加人均直接操作表；哈希需 BCrypt 生成）
-- INSERT INTO sys_user(username, name, password_hash, create_by, modified_by)
-- VALUES ('zhangsan', '张三', '$2a$10$XXXX...', 'admin', 'admin');
-- ----------------------------------------------------------------------------
