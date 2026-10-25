package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.troubleshoot.adapter.port.IDatabaseQueryPort;
import org.caps.columbo.domain.troubleshoot.model.properties.DatabaseProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.DbQueryResult;
import org.caps.columbo.domain.troubleshoot.model.valobj.DbTableInfo;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 数据库只读查询端口实现（JDBC MySQL）。
 * 安全护栏：语句白名单（SELECT/SHOW/DESC/EXPLAIN）→ 拒绝多语句/注释 → SELECT 自动补 LIMIT
 * → 语句超时 → 行数上限 → 单元格截断；连接只读（readOnly+自动提交关闭不落任何写）。
 */
@Slf4j
@Component
public class DatabaseQueryPortImpl implements IDatabaseQueryPort {

    /** 只允许这四类语句开头 */
    private static final Pattern ALLOWED_HEAD = Pattern.compile(
            "^(select|show|desc|describe|explain)\\b", Pattern.CASE_INSENSITIVE);

    /** 危险关键字（词边界匹配，覆盖 insert/update/delete/drop/alter/truncate/grant 等） */
    private static final Pattern DANGEROUS = Pattern.compile(
            "\\b(insert|update|delete|drop|alter|truncate|create|replace|grant|revoke|rename|lock|call|load_file|into\\s+outfile|into\\s+dumpfile|set\\b)\\b",
            Pattern.CASE_INSENSITIVE);

    /** SELECT 结尾已有 limit N（允许 limit 10 / limit 10,20） */
    private static final Pattern TAIL_LIMIT = Pattern.compile(
            "limit\\s+\\d+(\\s*,\\s*\\d+)?\\s*$", Pattern.CASE_INSENSITIVE);

    private static final int CELL_MAX_CHARS = 500;

    @Resource
    private DatabaseProperties properties;

    /** 每个连接配置一个轻量 Hikari 池（懒建，minIdle=0），替代高频 DriverManager 直连 */
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    @Override
    public List<DbTableInfo> listTables(String connectionName, String tableNameKeyword) {
        DatabaseProperties.Connection conn = requireConnection(connectionName);
        // information_schema 一次拿到表名+注释（SHOW TABLES 无注释，模型找表需要注释）；
        // 关键字走 PreparedStatement 参数绑定，杜绝字符串拼接注入面
        boolean filtered = StringUtils.hasText(tableNameKeyword);
        String sql = filtered
                ? "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE ? ESCAPE '\\' ORDER BY table_name"
                : "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name";
        String likeKeyword = filtered ? "%" + escapeLike(tableNameKeyword.trim()) + "%" : null;
        return executeQuery(conn, sql, likeKeyword, rs -> {
            List<DbTableInfo> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(DbTableInfo.builder()
                        .name(rs.getString(1))
                        .comment(rs.getString(2))
                        .build());
            }
            return tables;
        });
    }

    /** LIKE 转义：用户关键字中的 % _ \ 还原为字面匹配 */
    private static String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public String describeTable(String connectionName, String tableName) {
        DatabaseProperties.Connection conn = requireConnection(connectionName);
        if (!tableName.matches("[A-Za-z0-9_$]+")) {
            throw new IllegalArgumentException("表名只允许字母数字下划线: " + tableName);
        }
        return executeQuery(conn, "SHOW CREATE TABLE `" + tableName + "`", rs -> {
            rs.next();
            return rs.getString(2);
        });
    }

    @Override
    public DbQueryResult query(String connectionName, String sql) {
        DatabaseProperties.Connection conn = requireConnection(connectionName);
        String guarded = guardSql(sql);
        long start = System.currentTimeMillis();
        return executeQuery(conn, guarded, rs -> {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            List<String> columns = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
            int maxRows = properties.getMaxRows() == null ? 200 : properties.getMaxRows();
            List<List<String>> rows = new ArrayList<>();
            boolean truncated = false;
            while (rs.next()) {
                if (rows.size() >= maxRows) {
                    truncated = true;
                    break;
                }
                List<String> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    row.add(truncateCell(rs.getString(i)));
                }
                rows.add(row);
            }
            return DbQueryResult.builder()
                    .executedSql(guarded)
                    .columns(columns)
                    .rows(rows)
                    .rowCount(rows.size())
                    .truncated(truncated)
                    .costMs(System.currentTimeMillis() - start)
                    .build();
        });
    }

    // ==================== SQL 护栏 ====================

    /** 校验并改写 SQL：白名单、拒绝危险关键字/多语句/注释，SELECT 自动补 LIMIT */
    String guardSql(String sql) {
        String trimmed = sql == null ? "" : sql.trim().replaceAll(";+\\s*$", "");
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String lowered = trimmed.toLowerCase();
        if (lowered.contains(";") && lowered.indexOf(';') != lowered.length() - 1) {
            throw new IllegalArgumentException("拒绝多语句执行: " + trimmed);
        }
        if (lowered.contains("--") || lowered.contains("/*") || lowered.contains("#")) {
            throw new IllegalArgumentException("拒绝带注释的 SQL: " + trimmed);
        }
        if (!ALLOWED_HEAD.matcher(trimmed).find()) {
            throw new IllegalArgumentException("只允许 SELECT/SHOW/DESC/EXPLAIN 查询语句，拒绝: " + trimmed);
        }
        // 字符串字面量里的词不构成写操作（如 select 'created'、列别名"锁定数"），先摘除再检测，
        // 避免注释/字面量误伤合法查询
        String structural = trimmed.replaceAll("'[^']*'", "''").replaceAll("\"[^\"]*\"", "\"\"");
        if (DANGEROUS.matcher(structural).find()) {
            throw new IllegalArgumentException("SQL 含写操作/危险关键字，已拒绝: " + trimmed);
        }
        if (lowered.startsWith("select") && !TAIL_LIMIT.matcher(trimmed).find()) {
            int maxRows = properties.getMaxRows() == null ? 200 : properties.getMaxRows();
            trimmed = trimmed + " LIMIT " + maxRows;
        }
        return trimmed;
    }

    private DatabaseProperties.Connection requireConnection(String connectionName) {
        DatabaseProperties.Connection conn = properties.getConnections().get(connectionName);
        if (conn == null) {
            throw new IllegalArgumentException("未配置的数据库连接: " + connectionName
                    + "，可用连接见 listDbConnections");
        }
        return conn;
    }

    private <T> T executeQuery(DatabaseProperties.Connection conn, String sql,
                               ResultSetExtractor<T> extractor) {
        return executeQuery(conn, sql, null, extractor);
    }

    private <T> T executeQuery(DatabaseProperties.Connection conn, String sql, Object bindParam,
                               ResultSetExtractor<T> extractor) {
        int timeoutSeconds = properties.getTimeoutSeconds() == null ? 20 : properties.getTimeoutSeconds();
        try (Connection connection = pool(conn).getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                statement.setMaxRows((properties.getMaxRows() == null ? 200 : properties.getMaxRows()) + 1);
                if (bindParam != null) {
                    statement.setObject(1, bindParam);
                }
                try (ResultSet rs = statement.executeQuery()) {
                    return extractor.extract(rs);
                }
            }
        } catch (Exception e) {
            log.warn("数据库查询失败 conn={} sql={}", conn.getDatabase(), abbreviate(sql), e);
            throw new IllegalStateException("数据库查询失败: " + e.getMessage(), e);
        }
    }

    /** 连接池懒建：单连接配置最多 2 个活跃连接、空闲 60s 回收，零占用时无常驻连接 */
    private DataSource pool(DatabaseProperties.Connection conn) {
        String key = conn.getHost() + ":" + conn.getPort() + "/" + conn.getDatabase() + ":" + conn.getUser();
        return pools.computeIfAbsent(key, k -> {
            String url = String.format("jdbc:mysql://%s:%s/%s?useSSL=false&connectTimeout=5000&socketTimeout=%d&characterEncoding=utf8&useInformationSchema=true",
                    conn.getHost(), conn.getPort(), conn.getDatabase(),
                    timeoutSocketMs());
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(conn.getUser());
            config.setPassword(conn.getPassword());
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setIdleTimeout(60_000);
            config.setConnectionTimeout(5_000);
            config.setMaxLifetime(600_000);
            config.setPoolName("dbquery-" + k);
            return new HikariDataSource(config);
        });
    }

    private int timeoutSocketMs() {
        return (properties.getTimeoutSeconds() == null ? 20 : properties.getTimeoutSeconds()) * 1000 + 5000;
    }

    /** ResultSet 提取器：允许抛 SQLException（JDK Function 不支持受检异常） */
    @FunctionalInterface
    private interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws java.sql.SQLException;
    }

    private static String truncateCell(String value) {
        if (value == null) {
            return null;
        }
        String v = value.replace("\r", "").replace("\n", " ");
        return v.length() <= CELL_MAX_CHARS ? v : v.substring(0, CELL_MAX_CHARS) + "...(截断)";
    }

    private static String abbreviate(String text) {
        return text != null && text.length() <= 200 ? text : (text == null ? "" : text.substring(0, 200));
    }
}
