package org.caps.columbo.infrastructure.adapter.port;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SaTokenDao 的 JDBC 实现：登录 token 存会话库 satoken_store 表（H2/MySQL 随会话库切换），
 * 服务重启后 token 依然有效，7 天有效期真正落地。
 * 依赖约束：auth.enabled=true 时注册（覆盖 starter 默认的内存 Dao）；无会话库 DataSource 时整体回退内存。
 * 未使用的能力兜底：SaSession/Object 键走内存（本项目登录链路只用 String 键），searchData 返回空。
 * 过期惰性清理：读取时丢弃 + 启动时批量删一次（内部工具 token 量小，无需定时任务）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "auth", name = "enabled", havingValue = "true")
public class JdbcSaTokenDao implements SaTokenDao {

    private static final String MYSQL_DDL = """
            CREATE TABLE IF NOT EXISTS satoken_store (
                id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                store_key    VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '键(含sa-token前缀,如 satoken:login:token:xxx)',
                store_value  VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT '值(loginId等字符串)',
                expire_at    BIGINT          NOT NULL DEFAULT 0 COMMENT '过期毫秒时间戳,0=永不过期',
                gmt_create   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                gmt_modified DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                PRIMARY KEY (id),
                UNIQUE KEY uk_store_key (store_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录token存储(SaTokenDao JDBC实现)'""";

    private static final String H2_DDL = """
            CREATE TABLE IF NOT EXISTS satoken_store (
                id           BIGINT       NOT NULL AUTO_INCREMENT,
                store_key    VARCHAR(256) NOT NULL DEFAULT '',
                store_value  VARCHAR(1024) NOT NULL DEFAULT '',
                expire_at    BIGINT       NOT NULL DEFAULT 0,
                gmt_create   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                gmt_modified TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                CONSTRAINT uk_store_key UNIQUE (store_key)
            )""";

    /** Object 键/SaSession 的内存兜底条目（重启丢失无碍：本项目登录态只依赖 String 键） */
    private record MemEntry(Object value, long expireAt) {
        boolean alive() {
            return expireAt == 0 || expireAt > System.currentTimeMillis();
        }
    }

    private final DataSource dataSource;

    private final boolean h2;

    /** Object 键兜底存储（SaSession 按 objectMapper 键走这里） */
    private final Map<String, MemEntry> memoryStore = new ConcurrentHashMap<>();

    private volatile boolean schemaReady;

    /** Spring 装配：会话库池可选（session-store.enabled=false 时为 null，全内存回退） */
    @Autowired
    public JdbcSaTokenDao(@Autowired(required = false) @Qualifier("sessionStoreDataSource") DataSource dataSource,
                          SessionStoreProperties properties) {
        this.dataSource = dataSource;
        this.h2 = properties.getUrl().startsWith("jdbc:h2");
        if (dataSource == null) {
            log.warn("satoken 持久化未启用（无会话库 DataSource），token 存内存：服务重启后需重新登录");
        }
    }

    // ==================== String 键：登录态核心（落库） ====================

    @Override
    public String get(String key) {
        if (!ready()) {
            MemEntry entry = memoryStore.get(key);
            return entry != null && entry.alive() ? (String) entry.value() : null;
        }
        try {
            Row row = queryByKey(key);
            if (row == null) {
                return null;
            }
            if (expired(row.expireAt())) {
                executeUpdate("DELETE FROM satoken_store WHERE store_key = ?", key);
                return null;
            }
            return row.value();
        } catch (Exception e) {
            log.warn("satoken 读取失败（降级内存语义）key={}: {}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (!ready()) {
            memoryStore.put(key, new MemEntry(value, expireAtOf(timeout)));
            return;
        }
        try {
            ensureSchema();
            long expireAt = expireAtOf(timeout);
            String sql = h2
                    ? "MERGE INTO satoken_store(store_key, store_value, expire_at, gmt_modified) "
                    + "KEY(store_key) VALUES(?, ?, ?, CURRENT_TIMESTAMP)"
                    : "INSERT INTO satoken_store(store_key, store_value, expire_at) VALUES(?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE store_value = VALUES(store_value), expire_at = VALUES(expire_at), "
                    + "gmt_modified = CURRENT_TIMESTAMP";
            executeUpdate(sql, key, value, expireAt);
        } catch (Exception e) {
            log.warn("satoken 写入失败 key={}: {}", key, e.getMessage());
            memoryStore.put(key, new MemEntry(value, expireAtOf(timeout)));
        }
    }

    @Override
    public void update(String key, String value) {
        if (!ready()) {
            MemEntry old = memoryStore.get(key);
            if (old != null) {
                memoryStore.put(key, new MemEntry(value, old.expireAt()));
            }
            return;
        }
        try {
            executeUpdate("UPDATE satoken_store SET store_value = ?, gmt_modified = CURRENT_TIMESTAMP "
                    + "WHERE store_key = ?", value, key);
        } catch (Exception e) {
            log.warn("satoken 更新失败 key={}: {}", key, e.getMessage());
        }
    }

    @Override
    public void delete(String key) {
        memoryStore.remove(key);
        if (ready()) {
            try {
                executeUpdate("DELETE FROM satoken_store WHERE store_key = ?", key);
            } catch (Exception e) {
                log.warn("satoken 删除失败 key={}: {}", key, e.getMessage());
            }
        }
    }

    @Override
    public long getTimeout(String key) {
        if (!ready()) {
            MemEntry entry = memoryStore.get(key);
            return entry == null ? NOT_VALUE_EXPIRE : remainSeconds(entry.expireAt());
        }
        try {
            Row row = queryByKey(key);
            if (row == null) {
                return NOT_VALUE_EXPIRE;
            }
            if (expired(row.expireAt())) {
                executeUpdate("DELETE FROM satoken_store WHERE store_key = ?", key);
                return NOT_VALUE_EXPIRE;
            }
            return remainSeconds(row.expireAt());
        } catch (Exception e) {
            log.warn("satoken 查询超时失败 key={}: {}", key, e.getMessage());
            return NOT_VALUE_EXPIRE;
        }
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        if (!ready()) {
            MemEntry old = memoryStore.get(key);
            if (old != null) {
                memoryStore.put(key, new MemEntry(old.value(), expireAtOf(timeout)));
            }
            return;
        }
        try {
            executeUpdate("UPDATE satoken_store SET expire_at = ? WHERE store_key = ?", expireAtOf(timeout), key);
        } catch (Exception e) {
            log.warn("satoken 更新超时失败 key={}: {}", key, e.getMessage());
        }
    }

    // ==================== Object 键 / SaSession：内存兜底（本项目登录链路未使用） ====================

    @Override
    public Object getObject(String key) {
        MemEntry entry = memoryStore.get(key);
        return entry != null && entry.alive() ? entry.value() : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getObject(String key, Class<T> classOfT) {
        Object value = getObject(key);
        return value != null && classOfT.isInstance(value) ? (T) value : null;
    }

    @Override
    public void setObject(String key, Object object, long timeout) {
        memoryStore.put(key, new MemEntry(object, expireAtOf(timeout)));
    }

    @Override
    public void updateObject(String key, Object object) {
        MemEntry old = memoryStore.get(key);
        if (old != null) {
            memoryStore.put(key, new MemEntry(object, old.expireAt()));
        }
    }

    @Override
    public void deleteObject(String key) {
        memoryStore.remove(key);
    }

    @Override
    public long getObjectTimeout(String key) {
        MemEntry entry = memoryStore.get(key);
        return entry == null || !entry.alive() ? NOT_VALUE_EXPIRE : remainSeconds(entry.expireAt());
    }

    @Override
    public void updateObjectTimeout(String key, long timeout) {
        MemEntry old = memoryStore.get(key);
        if (old != null) {
            memoryStore.put(key, new MemEntry(old.value(), expireAtOf(timeout)));
        }
    }

    @Override
    public SaSession getSession(String sessionId) {
        Object value = memoryStore.get(sessionId);
        return value instanceof SaSession session ? session : null;
    }

    @Override
    public void setSession(SaSession session, long timeout) {
        memoryStore.put(session.getId(), new MemEntry(session, expireAtOf(timeout)));
    }

    @Override
    public void updateSession(SaSession session) {
        MemEntry old = memoryStore.get(session.getId());
        memoryStore.put(session.getId(), new MemEntry(session, old != null ? old.expireAt() : 0));
    }

    @Override
    public void deleteSession(String sessionId) {
        memoryStore.remove(sessionId);
    }

    @Override
    public long getSessionTimeout(String sessionId) {
        return getObjectTimeout(sessionId);
    }

    @Override
    public void updateSessionTimeout(String sessionId, long timeout) {
        updateObjectTimeout(sessionId, timeout);
    }

    /** 模糊搜索（分页枚举登录用户等场景）：本项目未使用，返回空 */
    @Override
    public List<String> searchData(String prefix, String keyword, int start, int size, boolean sortType) {
        return new ArrayList<>();
    }

    // ==================== 内部实现 ====================

    private boolean ready() {
        return dataSource != null;
    }

    private record Row(String value, long expireAt) {
    }

    private static boolean expired(long expireAt) {
        return expireAt != 0 && expireAt <= System.currentTimeMillis();
    }

    /** timeout 秒 → 过期毫秒时间戳；-1(NEVER_EXPIRE)=0 永不过期 */
    private static long expireAtOf(long timeout) {
        return timeout < 0 ? 0 : System.currentTimeMillis() + timeout * 1000;
    }

    /** 过期时间戳 → 剩余秒；永久返回 -1 */
    private static long remainSeconds(long expireAt) {
        return expireAt == 0 ? NEVER_EXPIRE : Math.max(0, (expireAt - System.currentTimeMillis()) / 1000);
    }

    private Row queryByKey(String key) throws Exception {
        ensureSchema();
        // Connection 必须显式持有并关闭：Statement.close() 不会归还池连接
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT store_value, expire_at FROM satoken_store WHERE store_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Row(rs.getString(1), rs.getLong(2)) : null;
            }
        }
    }

    private void executeUpdate(String sql, Object... args) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** 建表自愈 + 启动清理过期行（只做一次） */
    private void ensureSchema() throws Exception {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            try (Connection connection = dataSource.getConnection();
                 Statement stmt = connection.createStatement()) {
                stmt.execute(h2 ? H2_DDL : MYSQL_DDL);
                int cleaned = stmt.executeUpdate(
                        "DELETE FROM satoken_store WHERE expire_at > 0 AND expire_at < " + System.currentTimeMillis());
                if (cleaned > 0) {
                    log.info("satoken 启动清理过期 token {} 条", cleaned);
                }
            }
            schemaReady = true;
            log.info("satoken 持久化就绪 dialect={}", h2 ? "h2" : "mysql");
        }
    }
}
