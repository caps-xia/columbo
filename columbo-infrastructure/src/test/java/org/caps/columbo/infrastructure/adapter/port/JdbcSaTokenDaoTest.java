package org.caps.columbo.infrastructure.adapter.port;

import cn.dev33.satoken.dao.SaTokenDao;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionStoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SaTokenDao 的 JDBC 实现：写入/读取/过期/超时更新/重启恢复（两实例共用同一 H2 文件库）/内存回退
 */
class JdbcSaTokenDaoTest {

    @TempDir
    Path tempDir;

    private JdbcSaTokenDao newDao(String dbFile, DataSource dataSource) {
        SessionStoreProperties properties = new SessionStoreProperties();
        properties.setUrl("jdbc:h2:file:" + tempDir.resolve(dbFile) + ";MODE=MySQL");
        return new JdbcSaTokenDao(dataSource, properties);
    }

    /** 最小 DataSource（动态代理）：JDBC Dao 只用 getConnection() */
    private DataSource fileDataSource(String dbFile) {
        String url = "jdbc:h2:file:" + tempDir.resolve(dbFile) + ";MODE=MySQL";
        return (DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        return java.sql.DriverManager.getConnection(url, "sa", "");
                    }
                    if (method.getName().equals("toString")) {
                        return "test-datasource:" + dbFile;
                    }
                    return null;
                });
    }

    @Test
    void setGetAndTimeoutRoundTrip() {
        JdbcSaTokenDao dao = newDao("db1", fileDataSource("db1"));
        dao.set("satoken:login:token:t1", "caps", 604800);
        assertEquals("caps", dao.get("satoken:login:token:t1"));
        org.junit.jupiter.api.Assertions.assertTrue(dao.getTimeout("satoken:login:token:t1") > 604790,
                "剩余秒应≈604800");

        dao.update("satoken:login:token:t1", "zhangsan");
        assertEquals("zhangsan", dao.get("satoken:login:token:t1"));

        dao.updateTimeout("satoken:login:token:t1", SaTokenDao.NEVER_EXPIRE);
        assertEquals(SaTokenDao.NEVER_EXPIRE, dao.getTimeout("satoken:login:token:t1"));

        dao.delete("satoken:login:token:t1");
        assertNull(dao.get("satoken:login:token:t1"));
        assertEquals(SaTokenDao.NOT_VALUE_EXPIRE, dao.getTimeout("satoken:login:token:t1"));
    }

    @Test
    void expiredEntryIsLazilyDropped() {
        JdbcSaTokenDao dao = newDao("db2", fileDataSource("db2"));
        dao.set("k-expired", "v", 1);
        try {
            Thread.sleep(1100);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertNull(dao.get("k-expired"));
        assertEquals(SaTokenDao.NOT_VALUE_EXPIRE, dao.getTimeout("k-expired"));
    }

    /** 核心承诺：重启（新实例同库）后 token 仍有效、剩余超时连续 */
    @Test
    void tokenSurvivesRestart() {
        JdbcSaTokenDao first = newDao("db3", fileDataSource("db3"));
        first.set("satoken:login:token:persist", "caps", 604800);

        JdbcSaTokenDao restarted = newDao("db3", fileDataSource("db3"));
        assertEquals("caps", restarted.get("satoken:login:token:persist"));
        long remain = restarted.getTimeout("satoken:login:token:persist");
        org.junit.jupiter.api.Assertions.assertTrue(remain > 604000 && remain <= 604800,
                "剩余有效期应接近7天，实际=" + remain);

        restarted.delete("satoken:login:token:persist");
        assertNull(newDao("db3", fileDataSource("db3")).get("satoken:login:token:persist"));
    }

    /** 无 DataSource（会话库关闭）时全内存回退：可用但重启丢失 */
    @Test
    void fallsBackToMemoryWithoutDataSource() {
        JdbcSaTokenDao dao = newDao("db4", null);
        dao.set("k", "v", 60);
        assertEquals("v", dao.get("k"));
        dao.setObject("ko", "vo", 60);
        assertEquals("vo", dao.getObject("ko"));
        org.junit.jupiter.api.Assertions.assertTrue(dao.getTimeout("k") >= 59);
        org.junit.jupiter.api.Assertions.assertTrue(dao.searchData("satoken", "", 0, 10, true).isEmpty());
    }

    /** MySQL 方言标志不炸（DDL 语句生成路径由 h2 开关控制，此处仅验证构造与内存语义） */
    @Test
    void mysqlDialectFlagConstructed() throws Exception {
        SessionStoreProperties properties = new SessionStoreProperties();
        properties.setUrl("jdbc:mysql://localhost:3306/agent_session");
        JdbcSaTokenDao dao = new JdbcSaTokenDao(null, properties);
        dao.set("k", "v", 60);
        assertEquals("v", dao.get("k"));
    }
}
