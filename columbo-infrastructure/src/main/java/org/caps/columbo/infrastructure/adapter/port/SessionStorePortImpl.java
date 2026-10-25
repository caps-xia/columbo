package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionEntity;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionEventEntity;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionListRow;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionTokenEntity;
import org.caps.columbo.infrastructure.adapter.port.mapper.ChatSessionEventMapper;
import org.caps.columbo.infrastructure.adapter.port.mapper.ChatSessionMapper;
import org.caps.columbo.infrastructure.adapter.port.mapper.ChatSessionTokenMapper;
import org.caps.columbo.types.utils.GzipUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * 会话持久化端口实现（MyBatis-Plus + HikariCP，同库表结构、同幂等语义）。
 * 建库建表仍走 DriverManager（要连 server 级 URL，连接池建在库名之上）；
 * CRUD 走 Mapper；enabled=false 时 MyBatis 装配整体退避，本实现全部 no-op。
 * 写失败只抛给上层记日志（不阻断对话），读失败显式抛出。
 *
 * @author caps
 */
@Slf4j
@Component
public class SessionStorePortImpl implements ISessionStorePort {

    private static final String CREATE_DATABASE = "CREATE DATABASE IF NOT EXISTS agent_session DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";

    /** MySQL 生产档建表（按阿里规范：必备字段/审计/逻辑删除/默认值/uk_-idx_ 命名/注释） */
    private static final String[] MYSQL_CREATE_TABLES = {
            """
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话登记表'""",
            """
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话事件表(append-only,只增不改)'""",
            "ALTER TABLE chat_session ADD COLUMN title VARCHAR(512) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN space VARCHAR(64) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0",
            "ALTER TABLE chat_session ADD COLUMN create_by VARCHAR(128) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN modified_by VARCHAR(128) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session_event ADD COLUMN is_deleted TINYINT UNSIGNED NOT NULL DEFAULT 0",
            """
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话token用量表(按会话累计)'"""
    };

    /** H2 文件库档建表（结构与 MySQL 对齐；gmt_modified 由 touch 显式翻新，无 ON UPDATE） */
    private static final String[] H2_CREATE_TABLES = {
            """
            CREATE TABLE IF NOT EXISTS chat_session (
                id            BIGINT           NOT NULL AUTO_INCREMENT,
                app_name      VARCHAR(128)     NOT NULL DEFAULT '',
                session_id    VARCHAR(64)      NOT NULL DEFAULT '',
                user_id       VARCHAR(128)     NOT NULL DEFAULT '',
                title         VARCHAR(512)     NOT NULL DEFAULT '',
                space         VARCHAR(64)      NOT NULL DEFAULT '',
                is_deleted    TINYINT          NOT NULL DEFAULT 0,
                create_by     VARCHAR(128)     NOT NULL DEFAULT '',
                modified_by   VARCHAR(128)     NOT NULL DEFAULT '',
                gmt_create    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                gmt_modified  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                CONSTRAINT uk_app_session UNIQUE (app_name, session_id)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_user ON chat_session(user_id)",
            """
            CREATE TABLE IF NOT EXISTS chat_session_event (
                id            BIGINT           NOT NULL AUTO_INCREMENT,
                app_name      VARCHAR(128)     NOT NULL DEFAULT '',
                session_id    VARCHAR(64)      NOT NULL DEFAULT '',
                event_id      VARCHAR(64)      NOT NULL DEFAULT '',
                author        VARCHAR(64)      NOT NULL DEFAULT '',
                event_json    CLOB             NOT NULL,
                is_deleted    TINYINT          NOT NULL DEFAULT 0,
                gmt_create    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                CONSTRAINT uk_event UNIQUE (app_name, session_id, event_id)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_session_order ON chat_session_event(app_name, session_id, id)",
            "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS title VARCHAR(512) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS space VARCHAR(64) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS is_deleted TINYINT NOT NULL DEFAULT 0",
            "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS create_by VARCHAR(128) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session ADD COLUMN IF NOT EXISTS modified_by VARCHAR(128) NOT NULL DEFAULT ''",
            "ALTER TABLE chat_session_event ADD COLUMN IF NOT EXISTS is_deleted TINYINT NOT NULL DEFAULT 0",
            """
            CREATE TABLE IF NOT EXISTS chat_session_token (
                id            BIGINT           NOT NULL AUTO_INCREMENT,
                app_name      VARCHAR(128)     NOT NULL DEFAULT '',
                session_id    VARCHAR(64)      NOT NULL DEFAULT '',
                input_tokens  BIGINT           NOT NULL DEFAULT 0,
                output_tokens BIGINT           NOT NULL DEFAULT 0,
                cached_tokens BIGINT           NOT NULL DEFAULT 0,
                total_tokens  BIGINT           NOT NULL DEFAULT 0,
                llm_calls     BIGINT           NOT NULL DEFAULT 0,
                gmt_create    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                gmt_modified  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                CONSTRAINT uk_app_session_token UNIQUE (app_name, session_id)
            )"""
    };

    @Resource
    private SessionStoreProperties properties;

    /** enabled=false 时 SessionStoreMybatisConfig 整体退避，Mapper 不存在 */
    @Autowired(required = false)
    private ChatSessionMapper sessionMapper;

    @Autowired(required = false)
    private ChatSessionEventMapper eventMapper;

    @Autowired(required = false)
    private ChatSessionTokenMapper tokenMapper;

    /** 建库建表只需成功一次；MySQL 暂不可用时下次写入重试 */
    private volatile boolean schemaReady;

    @Override
    public void saveSession(String appName, String userId, String sessionId) {
        if (!enabled()) {
            return;
        }
        ensureSchema();
        ChatSessionEntity entity = new ChatSessionEntity();
        entity.setAppName(appName);
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        try {
            sessionMapper.upsert(entity);
        } catch (Exception e) {
            throw new IllegalStateException("会话写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void saveEvent(String appName, String userId, String sessionId, String eventId, String author, String eventJson) {
        if (!enabled()) {
            return;
        }
        ensureSchema();
        ChatSessionEventEntity entity = new ChatSessionEventEntity();
        entity.setAppName(appName);
        entity.setSessionId(sessionId);
        entity.setEventId(eventId);
        entity.setAuthor(author);
        // 长事件（排查日志结果等）超阈值自动 GZIP，短事件原样存；读侧 decompress 对两者都兼容
        entity.setEventJson(GzipUtils.compress(eventJson));
        try {
            eventMapper.insertIgnore(entity);
            sessionMapper.touch(appName, sessionId);
        } catch (Exception e) {
            throw new IllegalStateException("会话写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> loadEvents(String appName, String userId, String sessionId) {
        if (!enabled()) {
            return List.of();
        }
        ensureSchema();
        try {
            return eventMapper.selectEventJsons(appName, sessionId).stream()
                    .map(GzipUtils::decompress)
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("会话读取失败 app=" + appName + " session=" + sessionId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<ChatSessionRecordVO> loadSessions(String userId) {
        if (!enabled()) {
            return List.of();
        }
        ensureSchema();
        try {
            return sessionMapper.selectUserSessions(userId).stream()
                    .map(row -> ChatSessionRecordVO.builder()
                            .sessionId(row.getSessionId())
                            .appName(row.getAppName())
                            // first_user 是事件原文（可能已压缩），与 loadEvents 同步解压
                            .firstUserEventJson(GzipUtils.decompress(row.getFirstUser()))
                            .title(row.getTitle())
                            .space(row.getSpace())
                            .updatedAt(row.getUpdatedMs())
                            .build())
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("会话列表读取失败 user=" + userId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteSession(String appName, String userId, String sessionId) {
        if (!enabled()) {
            return;
        }
        ensureSchema();
        try {
            // 以库中真实 appName 为准：调用方的 appName 来自 agentId 反解，
            // agent 已下线（如演示 agent 移除后的孤儿会话）或传错时按原值删会静默匹配 0 行
            String realAppName = sessionMapper.selectAppNameByUserSession(userId, sessionId);
            String appNameToDelete = realAppName != null ? realAppName : appName;
            // 逻辑删除（阿里规范）：行保留、is_deleted=1；会话行记操作者，事件表 append-only 无审计列
            eventMapper.markDeleted(appNameToDelete, sessionId);
            sessionMapper.markDeleted(appNameToDelete, sessionId, userId);
            // 用量是派生数据：随会话物理清理（无审计价值，不参与软删语义）
            if (tokenMapper != null) {
                tokenMapper.deleteBySession(appNameToDelete, sessionId);
            }
        } catch (Exception e) {
            throw new IllegalStateException("会话删除失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean renameSession(String appName, String userId, String sessionId, String title) {
        if (!enabled()) {
            return false;
        }
        ensureSchema();
        try {
            // 与删除同款保护：按 userId+sessionId 定位，agent 下线的孤儿会话也能改名
            return sessionMapper.renameSession(userId, sessionId, title, userId) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("会话重命名失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean moveSession(String appName, String userId, String sessionId, String space) {
        if (!enabled()) {
            return false;
        }
        ensureSchema();
        try {
            // 空串归位到"未归档"（space=NULL）；NULLIF 双方言通用
            return sessionMapper.moveSession(userId, sessionId, space, userId) > 0;
        } catch (Exception e) {
            throw new IllegalStateException("会话移动失败: " + e.getMessage(), e);
        }
    }

    /** 列存在才执行改名（幂等）：DatabaseMetaData 大小写不敏感探测，H2/MySQL 通用 */
    private static void renameColumnIfPresent(Connection connection, Statement stmt,
                                               String table, String from, String to) throws Exception {
        try (var rs = connection.getMetaData().getColumns(null, null, table.toUpperCase(), from.toUpperCase())) {
            if (!rs.next()) {
                return;
            }
        }
        String sql = "ALTER TABLE " + table + " RENAME COLUMN " + from + " TO " + to;
        try {
            stmt.execute(sql);
            log.info("旧库列已改名 {} : {} -> {}", table, from, to);
        } catch (Exception e) {
            // MySQL 8 以下用 CHANGE 语法兜底
            if (!e.getMessage().toLowerCase().contains("syntax")) {
                throw e;
            }
            stmt.execute("ALTER TABLE " + table + " CHANGE COLUMN " + from + " " + to + " DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
            log.info("旧库列已改名(CHANGE) {} : {} -> {}", table, from, to);
        }
    }

    @Override
    public void recordTokenUsage(String appName, String userId, String sessionId, TokenUsageVO delta) {
        if (!enabled() || delta == null || delta.isEmpty()) {
            return;
        }
        ensureSchema();
        try {
            // 累加走「UPDATE 命中 0 行再 INSERT」：不依赖方言 upsert 语法，H2/MySQL 通用
            int updated = tokenMapper.accumulate(appName, sessionId,
                    delta.getInputTokens(), delta.getOutputTokens(), delta.getCachedTokens(),
                    delta.getTotalTokens(), delta.getLlmCalls());
            if (updated == 0) {
                try {
                    tokenMapper.insertUsage(appName, sessionId,
                            delta.getInputTokens(), delta.getOutputTokens(), delta.getCachedTokens(),
                            delta.getTotalTokens(), delta.getLlmCalls());
                } catch (Exception duplicate) {
                    // 并发首写撞 uk：退回累加一次
                    tokenMapper.accumulate(appName, sessionId,
                            delta.getInputTokens(), delta.getOutputTokens(), delta.getCachedTokens(),
                            delta.getTotalTokens(), delta.getLlmCalls());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("会话用量写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public TokenUsageVO loadTokenUsage(String appName, String sessionId) {
        if (!enabled()) {
            return TokenUsageVO.builder().build();
        }
        ensureSchema();
        try {
            ChatSessionTokenEntity row = tokenMapper.selectBySession(appName, sessionId);
            if (row == null) {
                return TokenUsageVO.builder().build();
            }
            return TokenUsageVO.builder()
                    .inputTokens(nz(row.getInputTokens()))
                    .outputTokens(nz(row.getOutputTokens()))
                    .cachedTokens(nz(row.getCachedTokens()))
                    .totalTokens(nz(row.getTotalTokens()))
                    .llmCalls(nz(row.getLlmCalls()))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("会话用量读取失败: " + e.getMessage(), e);
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private boolean enabled() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getUrl())
                && sessionMapper != null
                && eventMapper != null
                && tokenMapper != null;
    }

    /** 自愈建库建表（幂等，仅成功一次）。MySQL 要先连 server 级 URL 建库；H2 文件库无库概念，只建表 */
    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            boolean h2 = properties.getUrl().startsWith("jdbc:h2");
            if (!h2) {
                // 建库要连 server 级 URL（不带库名）：jdbc:mysql://host:port/?...
                String serverUrl = properties.getUrl().replaceAll("(jdbc:mysql://[^/]+)/[^?]*", "$1/");
                try (Connection connection = DriverManager.getConnection(serverUrl, properties.getUsername(), properties.getPassword());
                     Statement stmt = connection.createStatement()) {
                    stmt.execute(CREATE_DATABASE);
                } catch (Exception e) {
                    throw new IllegalStateException("建库失败: " + e.getMessage(), e);
                }
            }
            for (String ddl : h2 ? H2_CREATE_TABLES : MYSQL_CREATE_TABLES) {
                try (Connection connection = DriverManager.getConnection(properties.getUrl(), properties.getUsername(), properties.getPassword());
                     Statement stmt = connection.createStatement()) {
                    // MySQL 没有 ADD COLUMN IF NOT EXISTS：补列类 DDL 先探测列是否已存在，存在即跳过，
                    // 不再依赖执行报错信息匹配（旧库升级与新库全量建表都幂等）
                    if (columnAlreadyPresent(connection, ddl)) {
                        continue;
                    }
                    stmt.execute(ddl);
                } catch (Exception e) {
                    // 兜底：并发启动等场景下仍可能撞 duplicate column，容忍跳过
                    if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate column")) {
                        continue;
                    }
                    throw new IllegalStateException("建表失败: " + e.getMessage(), e);
                }
            }
            // 旧库列改名（created_at→gmt_create 系）：先探测列存在才执行——
            // RENAME 无 IF EXISTS 形态，已迁移过的库再跑会报"列不存在"导致建表阶段整体失败
            try (Connection connection = DriverManager.getConnection(properties.getUrl(), properties.getUsername(), properties.getPassword());
                 Statement stmt = connection.createStatement()) {
                renameColumnIfPresent(connection, stmt, "chat_session", "created_at", "gmt_create");
                renameColumnIfPresent(connection, stmt, "chat_session", "updated_at", "gmt_modified");
                renameColumnIfPresent(connection, stmt, "chat_session_event", "created_at", "gmt_create");
            } catch (Exception e) {
                throw new IllegalStateException("建表失败(列改名): " + e.getMessage(), e);
            }
            schemaReady = true;
            log.info("会话存储就绪 dialect={} url={}", h2 ? "h2" : "mysql", properties.getUrl());
        }
    }

    /** 补列 DDL（ALTER TABLE t ADD COLUMN c ...）先探测列是否已存在；非补列 DDL 一律返回 false */
    private boolean columnAlreadyPresent(Connection connection, String ddl) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(ddl);
        if (!matcher.find()) {
            return false;
        }
        String table = matcher.group(1);
        String column = matcher.group(2);
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT COUNT(*) FROM information_schema.columns "
                             + "WHERE table_schema = CURRENT_SCHEMA AND table_name = '" + table.toLowerCase()
                             + "' AND lower(column_name) = '" + column.toLowerCase() + "'")) {
            return rs.next() && rs.getInt(1) > 0;
        } catch (Exception e) {
            // 探测失败（老库无 information_schema 权限等）按不存在处理，走执行 + duplicate column 兜底
            return false;
        }
    }
}
