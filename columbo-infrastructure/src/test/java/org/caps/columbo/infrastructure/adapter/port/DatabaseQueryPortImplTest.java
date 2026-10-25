package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.troubleshoot.model.properties.DatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SQL 护栏单元测试：白名单/危险关键字/多语句/注释/LIMIT 自动补全
 */
class DatabaseQueryPortImplTest {

    private DatabaseQueryPortImpl port;

    @BeforeEach
    void setUp() {
        port = new DatabaseQueryPortImpl();
        DatabaseProperties properties = new DatabaseProperties();
        ReflectionTestUtils.setField(port, "properties", properties);
    }

    @Test
    void select自动补LIMIT() {
        String guarded = port.guardSql("SELECT * FROM t_order WHERE status = 1");
        assertEquals("SELECT * FROM t_order WHERE status = 1 LIMIT 200", guarded);
    }

    @Test
    void 已带LIMIT不改写() {
        assertEquals("SELECT * FROM t_order LIMIT 10",
                port.guardSql("SELECT * FROM t_order LIMIT 10"));
        assertEquals("select * from t_order limit 5, 10",
                port.guardSql("select * from t_order limit 5, 10"));
    }

    @Test
    void show与explain与desc放行() {
        assertEquals("show tables", port.guardSql("show tables"));
        assertEquals("EXPLAIN SELECT * FROM t LIMIT 1", port.guardSql("EXPLAIN SELECT * FROM t LIMIT 1"));
        assertEquals("desc t_order", port.guardSql("desc t_order"));
    }

    @Test
    void 写操作全部拒绝() {
        String[] attacks = {
                "DELETE FROM t_order",
                "UPDATE t_order SET status = 0",
                "INSERT INTO t VALUES (1)",
                "DROP TABLE t_order",
                "TRUNCATE TABLE t_order",
                "ALTER TABLE t ADD COLUMN x INT",
                "CREATE TABLE hack (id INT)",
                "select * from t; drop table t",
                "SELECT * FROM t -- comment",
                "SELECT * FROM t /* c */",
                "REPLACE INTO t VALUES (1)",
                "GRANT ALL ON *.* TO 'x'",
                "LOAD_FILE('/etc/passwd')",
                "SELECT * FROM t INTO OUTFILE '/tmp/x'",
        };
        for (String sql : attacks) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> port.guardSql(sql), "应当拒绝: " + sql);
            assertTrue(e.getMessage().contains("拒绝"), "拒绝信息: " + e.getMessage());
        }
    }

    @Test
    void 非查询语句拒绝() {
        assertThrows(IllegalArgumentException.class, () -> port.guardSql("SET @a = 1"));
        assertThrows(IllegalArgumentException.class, () -> port.guardSql("CALL proc_x()"));
        assertThrows(IllegalArgumentException.class, () -> port.guardSql(""));
    }

    @Test
    void select内嵌替换函数等写风险关键字也拒绝() {
        // replace() 函数与 REPLACE INTO 同名，宁可误拒不可漏放
        assertThrows(IllegalArgumentException.class,
                () -> port.guardSql("SELECT REPLACE(name, 'a', 'b') FROM t"));
    }

    @Test
    void 常规业务列名不误伤() {
        assertEquals(
                "SELECT created_at, updated_time, offset_flag, description FROM t_order WHERE created_at > '2026-09-01' LIMIT 200",
                port.guardSql("SELECT created_at, updated_time, offset_flag, description FROM t_order WHERE created_at > '2026-09-01'"));
    }
}
