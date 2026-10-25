import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H2 → MySQL 一次性数据迁移工具（columbo 会话库）。
 *
 * <p>用途：把默认 H2 文件库（./data/agent_session.mv.db）里的会话/用户/token 数据
 * 全量迁到 MySQL，供 ai.agent.session-store.url 切 MySQL 后无缝续用。
 * 目标库表用与 SessionStorePortImpl/UserRepositoryImpl/JdbcSaTokenDao 完全一致的
 * MySQL 档 DDL 预建（应用侧 ensureSchema 幂等，启动后发现表齐即直接读写）。
 *
 * <p>用法（本机 JDK 17+，驱动 jar 取自本机 maven 仓库）：
 * <pre>
 * CP="$H2_JAR:$MYSQL_JAR"
 * java -cp "$CP" H2ToMysql.java \
 *     /abs/path/to/data/agent_session \
 *     jdbc:mysql://127.0.0.1:13306 root 123456 agent_session
 * </pre>
 * 幂等：目标表有数据时先清空再迁（closeEnough 语义为"以 H2 为准"），重复执行安全。
 */
public class H2ToMysql {

    /** 与 SessionStorePortImpl.MYSQL_CREATE_TABLES 对齐（含补列后的最终形态） */
    private static final String[] TABLE_DDLS = {
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
                author        VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '事件作者(user/model)',
                event_json    LONGTEXT         NOT NULL COMMENT '事件JSON原文(H2源无上限,存在超MEDIUMTEXT的历史大事件,故用LONGTEXT)',
                is_deleted    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除:0-否,1-是(随会话软删)',
                gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                PRIMARY KEY (id),
                UNIQUE KEY uk_event (app_name, session_id, event_id),
                KEY idx_session_order (app_name, session_id, id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话事件表(append-only)'""",
            """
            CREATE TABLE IF NOT EXISTS chat_session_token (
                id            BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT COMMENT '主键',
                app_name      VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '应用名',
                session_id    VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '会话ID',
                input_tokens  BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计输入token(含缓存命中)',
                output_tokens BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计输出token',
                cached_tokens BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '其中提示缓存命中token',
                total_tokens  BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '累计总token',
                llm_calls     BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '模型调用次数',
                gmt_create    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                gmt_modified  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                PRIMARY KEY (id),
                UNIQUE KEY uk_app_session (app_name, session_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话token用量表'""",
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表(登录账号)'""",
            """
            CREATE TABLE IF NOT EXISTS satoken_store (
                id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
                store_key    VARCHAR(256)    NOT NULL DEFAULT '' COMMENT '键(含sa-token前缀)',
                store_value  VARCHAR(1024)   NOT NULL DEFAULT '' COMMENT '值(loginId等字符串)',
                expire_at    BIGINT          NOT NULL DEFAULT 0 COMMENT '过期毫秒时间戳,0=永不过期',
                gmt_create   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                gmt_modified DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                PRIMARY KEY (id),
                UNIQUE KEY uk_store_key (store_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录token存储'"""
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("用法: H2ToMysql <h2文件路径不含扩展名> <mysql地址如 jdbc:mysql://127.0.0.1:13306> <用户> <密码> <库名>");
            System.exit(2);
        }
        String h2Path = args[0];
        String mysqlHost = args[1];
        String user = args[2];
        String pass = args[3];
        String dbName = args[4];

        String h2Url = "jdbc:h2:file:" + h2Path + ";MODE=MySQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;IFEXISTS=TRUE";
        // maxAllowedPacket 告知驱动服务端上限（客户端同步放宽）；服务端全局值在下方 SET GLOBAL 提到 256M
        String packetParams = "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
                + "&connectTimeout=5000&socketTimeout=600000&rewriteBatchedStatements=true&maxAllowedPacket=268435456";
        String mysqlServerUrl = mysqlHost + "/?" + packetParams;
        String mysqlDbUrl = mysqlHost + "/" + dbName + "?" + packetParams;

        try (Connection h2 = DriverManager.getConnection(h2Url, "sa", "")) {
            System.out.println("[1/4] H2 已连接: " + h2Url);
            List<String> tables = sourceTables(h2);
            System.out.println("      源库表: " + tables);

            try (Connection server = DriverManager.getConnection(mysqlServerUrl, user, pass)) {
                try (Statement st = server.createStatement()) {
                    st.execute("CREATE DATABASE IF NOT EXISTS " + dbName
                            + " DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
                // 历史大事件单行可超默认 64M 包上限：全局提到 256M（对新连接生效，开发机无副作用）
                try (Statement st = server.createStatement()) {
                    st.execute("SET GLOBAL max_allowed_packet = 268435456");
                    System.out.println("      服务端 max_allowed_packet 已临时提到 256M");
                } catch (Exception e) {
                    System.out.println("      提高服务端 max_allowed_packet 失败（无权限？），超大单行事件可能失败: " + e.getMessage());
                }
            }
            System.out.println("[2/4] 目标库就绪: " + dbName);

            try (Connection mysql = DriverManager.getConnection(mysqlDbUrl, user, pass)) {
                // 先删后建：迁移以 H2 为准（目标表可能是上次中断的残缺结构），幂等可重跑
                try (Statement st = mysql.createStatement()) {
                    for (String ddl : TABLE_DDLS) {
                        st.execute("DROP TABLE IF EXISTS " + tableNameOf(ddl));
                    }
                    for (String ddl : TABLE_DDLS) {
                        st.execute(ddl);
                    }
                }
                System.out.println("[3/4] 目标表就绪（5 张，与应用 DDL 对齐）");

                for (String table : tables) {
                    long rows = copyTable(h2, mysql, table);
                    long target = count(mysql, table);
                    System.out.printf("      %-22s 迁移 %d 行，目标表现有 %d 行%n", table, rows, target);
                }

                System.out.println("[4/4] 校验（两侧行数对照）:");
                for (String table : tables) {
                    System.out.printf("      %-22s H2=%-8d MySQL=%-8d %s%n", table,
                            count(h2, table), count(mysql, table),
                            count(h2, table) == count(mysql, table) ? "OK" : "MISMATCH!");
                }
            }
        }
        System.out.println("迁移完成。接下来把 ai.agent.session-store.url 切到 MySQL 即可。");
    }

    /** 从建表 DDL 里解析表名（DDL 均以 CREATE TABLE IF NOT EXISTS <表名> ( 开头） */
    private static String tableNameOf(String ddl) {
        String after = ddl.substring(ddl.indexOf("EXISTS ") + "EXISTS ".length());
        return after.substring(0, after.indexOf('(')).trim();
    }

    /** 源库实际存在的业务表（过滤 H2 系统表），固定顺序保证输出稳定 */
    private static List<String> sourceTables(Connection h2) throws Exception {
        List<String> wanted = List.of("chat_session", "chat_session_event", "chat_session_token",
                "sys_user", "satoken_store");
        List<String> present = new ArrayList<>();
        try (ResultSet rs = h2.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME").toLowerCase();
                if (wanted.contains(name)) {
                    present.add(name);
                }
            }
        }
        return wanted.stream().filter(present::contains).toList();
    }

    /** 全量复制一张表（列名取自源表元数据，按名对齐写入目标；目标先清空） */
    private static long copyTable(Connection source, Connection target, String table) throws Exception {
        Map<String, Integer> columns = new LinkedHashMap<>();
        try (ResultSet rs = source.createStatement().executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                columns.put(meta.getColumnLabel(i).toLowerCase(), i);
            }
        }
        String colList = String.join(", ", columns.keySet());
        String placeholders = String.join(", ", columns.values().stream().map(v -> "?").toList());

        try (Statement del = target.createStatement()) {
            del.execute("DELETE FROM " + table);
        }
        // H2 旧行的补列可能为 NULL，MySQL NOT NULL 列拒绝显式 NULL：按目标列可空性/类型做兜底
        Map<String, Integer> nullability = new LinkedHashMap<>();
        Map<String, Integer> jdbcTypes = new LinkedHashMap<>();
        try (ResultSet rs = target.createStatement().executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                String name = meta.getColumnLabel(i).toLowerCase();
                nullability.put(name, meta.isNullable(i));
                jdbcTypes.put(name, meta.getColumnType(i));
            }
        }
        long total = 0;
        try (ResultSet rs = source.createStatement().executeQuery("SELECT * FROM " + table);
             PreparedStatement insert = target.prepareStatement(
                     "INSERT INTO " + table + " (" + colList + ") VALUES (" + placeholders + ")")) {
            int batch = 0;
            long batchBytes = 0;
            while (rs.next()) {
                int idx = 1;
                long rowBytes = 0;
                for (Map.Entry<String, Integer> column : columns.entrySet()) {
                    Object value = rs.getObject(column.getValue());
                    if (value == null && nullability.getOrDefault(column.getKey(), 0) == 0) {
                        value = defaultValueFor(jdbcTypes.get(column.getKey()));
                    }
                    if (value instanceof String text) {
                        // utf8mb4 最宽 4 字节/字符，按上界估体积
                        rowBytes += 4L * text.length();
                    }
                    insert.setObject(idx++, value);
                }
                // rewriteBatchedStatements 会把整批拼成一条多值 INSERT：
                // 行数与累计体积双阈值刷批（先刷后加，超大行独占一批），避开 max_allowed_packet
                if (batch > 0 && (batch >= 200 || batchBytes + rowBytes >= 8 * 1024 * 1024)) {
                    total += insert.executeBatch().length;
                    batch = 0;
                    batchBytes = 0;
                }
                insert.addBatch();
                batch++;
                batchBytes += rowBytes;
            }
            if (batch > 0) {
                total += insert.executeBatch().length;
            }
        }
        return total;
    }

    /** NOT NULL 列的 NULL 兜底值：字符类空串、数值类 0、时间类当前时间 */
    private static Object defaultValueFor(Integer jdbcType) {
        if (jdbcType == null) {
            return "";
        }
        return switch (jdbcType) {
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER,
                 java.sql.Types.BIGINT, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC,
                 java.sql.Types.FLOAT, java.sql.Types.DOUBLE, java.sql.Types.REAL, java.sql.Types.BIT,
                 java.sql.Types.BOOLEAN -> 0;
            case java.sql.Types.TIMESTAMP, java.sql.Types.DATE, java.sql.Types.TIME
                    -> new java.sql.Timestamp(System.currentTimeMillis());
            default -> "";
        };
    }

    private static long count(Connection connection, String table) throws Exception {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
