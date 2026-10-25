package org.caps.columbo.domain.troubleshoot.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库直连配置（只读排查用）。连接注册表：key=连接名（工具的 connection 参数）。
 * 仅暴露查询能力：网关层强制 SELECT/SHOW/DESC/EXPLAIN 白名单 + LIMIT + 超时。
 */
@Data
@ConfigurationProperties(prefix = "troubleshoot.database", ignoreInvalidFields = true)
public class DatabaseProperties {

    /** 单次查询返回的最大行数 */
    private Integer maxRows = 200;

    /** 单条语句执行超时（秒） */
    private Integer timeoutSeconds = 20;

    /** 连接注册表：key 如 tongxin_hospital */
    private Map<String, Connection> connections = new LinkedHashMap<>();

    @Data
    public static class Connection {

        /** 连接展示名（含环境说明），如: 商城库（测试环境） */
        private String label;

        /** JDBC 主机 */
        private String host;

        /** 端口 */
        private String port;

        /** 库名 */
        private String database;

        /** 用户名（只读账号） */
        private String user;

        /** 密码 */
        private String password;
    }
}
