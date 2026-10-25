package org.caps.columbo.infrastructure.adapter.repository;

import org.caps.columbo.domain.auth.adapter.port.IUserRepository;
import org.caps.columbo.domain.auth.model.entity.AuthUser;
import org.caps.columbo.domain.auth.model.properties.AuthProperties;
import org.caps.columbo.infrastructure.adapter.port.entity.SysUserEntity;
import org.caps.columbo.infrastructure.adapter.port.mapper.SysUserMapper;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Optional;

/**
 * 用户仓储实现：sys_user 表（按阿里规范建表，复用会话库连接池）。
 * 首次访问自愈建表；表空时按 auth.seed 初始化种子账号（BCrypt 哈希、create_by=system）。
 * 逻辑删除的账号不参与登录与注册查重；H2 无 ON UPDATE，gmt_modified 由更新方显式翻新。
 */
@Slf4j
@Component
public class UserRepositoryImpl implements IUserRepository {

    /** MySQL 生产档建表（字段 NOT NULL+默认值，注释齐全，uk_/idx_ 命名） */
    private static final String[] MYSQL_CREATE_TABLES = {
            """
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(登录账号)'"""
    };

    /** H2 文件库档建表（无 ON UPDATE/内联列注释，其余规范对齐） */
    private static final String[] H2_CREATE_TABLES = {
            """
            CREATE TABLE IF NOT EXISTS sys_user (
                id            BIGINT           NOT NULL AUTO_INCREMENT,
                username      VARCHAR(64)      NOT NULL DEFAULT '',
                name          VARCHAR(128)     NOT NULL DEFAULT '',
                password_hash VARCHAR(128)     NOT NULL DEFAULT '',
                is_deleted    TINYINT          NOT NULL DEFAULT 0,
                create_by     VARCHAR(64)      NOT NULL DEFAULT '',
                modified_by   VARCHAR(64)      NOT NULL DEFAULT '',
                gmt_create    TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                gmt_modified  TIMESTAMP        NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                UNIQUE (username)
            )""",
            "CREATE INDEX IF NOT EXISTS idx_gmt_create ON sys_user(gmt_create)"
    };

    @Autowired(required = false)
    private SysUserMapper sysUserMapper;

    @Autowired
    private org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties sessionStoreProperties;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 建表+种子只需成功一次 */
    private volatile boolean schemaReady;

    @Override
    public Optional<AuthUser> findByUsername(String username) {
        if (!StringUtils.hasText(username) || !mapperReady()) {
            return Optional.empty();
        }
        SysUserEntity entity = sysUserMapper.selectByUsername(username.trim());
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(AuthUser.builder()
                .username(entity.getUsername())
                .name(entity.getName())
                .passwordHash(entity.getPasswordHash())
                .build());
    }

    @Override
    public boolean existsByUsername(String username) {
        if (!StringUtils.hasText(username) || !mapperReady()) {
            return false;
        }
        return sysUserMapper.selectByUsername(username.trim()) != null;
    }

    @Override
    public void save(AuthUser user, String operator) {
        if (!mapperReady()) {
            throw new AppException(ResponseCode.B001.getCode(),
                    "用户表不可用：登录功能依赖会话库（ai.agent.session-store.enabled 需为 true，默认即开）");
        }
        try {
            sysUserMapper.insertUser(user.getUsername(), user.getName(), user.getPasswordHash(), operator);
        } catch (DuplicateKeyException e) {
            // 工号唯一键兜底（并发注册同一工号）
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "工号已存在：" + user.getUsername());
        }
    }

    /** 建表+种子自愈；mapper 不可用（会话库关闭）时抛明确错误 */
    private boolean mapperReady() {
        ensureSchema();
        return sysUserMapper != null;
    }

    private void ensureSchema() {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            if (sysUserMapper == null) {
                throw new AppException(ResponseCode.B001.getCode(),
                        "用户表不可用：登录功能依赖会话库（ai.agent.session-store.enabled 需为 true，默认即开）");
            }
            boolean h2 = sessionStoreProperties.getUrl().startsWith("jdbc:h2");
            try {
                // 全部 DDL 走同一连接顺序执行：避免 H2 文件库跨连接的表可见性时序问题
                try (Connection connection = DriverManager.getConnection(
                        sessionStoreProperties.getUrl(), sessionStoreProperties.getUsername(),
                        sessionStoreProperties.getPassword());
                     Statement stmt = connection.createStatement()) {
                    for (String ddl : h2 ? H2_CREATE_TABLES : MYSQL_CREATE_TABLES) {
                        stmt.execute(ddl);
                    }
                }
                seedIfEmpty();
                schemaReady = true;
                log.info("用户表就绪 dialect={}", h2 ? "h2" : "mysql");
            } catch (Exception e) {
                throw new AppException(ResponseCode.B001.getCode(), "用户表初始化失败: " + e.getMessage(), e);
            }
        }
    }

    /** 表空时按 auth.seed 建种子账号（哈希入库，create_by=system；后续改密直接 update 表） */
    private void seedIfEmpty() {
        if (sysUserMapper.countActive() > 0) {
            return;
        }
        AuthProperties.Seed seed = authProperties.getSeed();
        if (seed == null || !StringUtils.hasText(seed.getUsername())) {
            return;
        }
        sysUserMapper.insertUser(seed.getUsername().trim(),
                StringUtils.hasText(seed.getName()) ? seed.getName().trim() : seed.getUsername().trim(),
                passwordEncoder.encode(seed.getPassword()),
                "system");
        log.info("已初始化种子账号 username={}（改密码请直接 update sys_user 表）", seed.getUsername().trim());
    }
}
