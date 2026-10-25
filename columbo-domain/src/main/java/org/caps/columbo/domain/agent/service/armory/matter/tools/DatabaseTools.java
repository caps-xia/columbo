package org.caps.columbo.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.caps.columbo.domain.troubleshoot.adapter.port.IDatabaseQueryPort;
import org.caps.columbo.domain.troubleshoot.model.properties.DatabaseProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.DbQueryResult;
import org.caps.columbo.domain.troubleshoot.model.valobj.DbTableInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据库只读查询工具集（@Tool 暴露给 LLM）。
 * 仅 SELECT/SHOW/DESC/EXPLAIN；网关层强制 LIMIT、超时、行数上限，任何写操作直接拒绝。
 */
@Slf4j
@Service
public class DatabaseTools {

    @Resource
    private IDatabaseQueryPort databaseQueryPort;

    @Resource
    private DatabaseProperties databaseProperties;

    @Tool(description = "列出可用的数据库连接及各自说明。排查数据问题前先调用它确认连接名（connection 参数），不同连接对应不同库/环境。")
    public DbConnectionListResult listDbConnections() {
        try {
            List<DbConnectionInfo> infos = new ArrayList<>();
            databaseProperties.getConnections().forEach((name, conn) -> infos.add(DbConnectionInfo.builder()
                    .connection(name)
                    .label(conn.getLabel())
                    .database(conn.getDatabase())
                    .build()));
            return DbConnectionListResult.builder().success(true).connections(infos).build();
        } catch (Exception e) {
            return DbConnectionListResult.builder().success(false).errorMsg(e.getMessage()).build();
        }
    }

    @Tool(description = "列出指定库中的数据表（表名+表注释）。不知道数据存在哪张表时先用它，可用 tableNameKeyword 按表名过滤（如 order、patient）。")
    public DbTableListResult listDbTables(DbTableListRequest request) {
        try {
            if (request == null || isBlank(request.getConnection())) {
                return DbTableListResult.builder().success(false)
                        .errorMsg("请提供 connection（连接名，见 listDbConnections 返回）").build();
            }
            List<DbTableInfo> tables = databaseQueryPort.listTables(
                    request.getConnection().trim(), request.getTableNameKeyword());
            int max = 300;
            boolean truncated = tables != null && tables.size() > max;
            List<DbTableInfo> clipped = truncated ? tables.subList(0, max) : tables;
            return DbTableListResult.builder()
                    .success(true)
                    .connection(request.getConnection().trim())
                    .returned(clipped == null ? 0 : clipped.size())
                    .truncated(truncated)
                    .tables(clipped)
                    .build();
        } catch (Exception e) {
            log.warn("数据库表清单查询异常", e);
            return DbTableListResult.builder().success(false).errorMsg("查询表清单失败: " + e.getMessage()).build();
        }
    }

    @Tool(description = "查看表结构（SHOW CREATE TABLE 全文：字段、类型、默认值、注释、索引）。写 SQL 前先看它确认字段名与含义，避免瞎猜列名。")
    public DbDescribeResult describeDbTable(DbDescribeRequest request) {
        try {
            if (request == null || isBlank(request.getConnection()) || isBlank(request.getTableName())) {
                return DbDescribeResult.builder().success(false)
                        .errorMsg("请提供 connection 与 tableName").build();
            }
            String ddl = databaseQueryPort.describeTable(request.getConnection().trim(), request.getTableName().trim());
            return DbDescribeResult.builder()
                    .success(true)
                    .connection(request.getConnection().trim())
                    .tableName(request.getTableName().trim())
                    .createTable(ddl)
                    .build();
        } catch (Exception e) {
            log.warn("表结构查询异常", e);
            return DbDescribeResult.builder().success(false).errorMsg("查询表结构失败: " + e.getMessage()).build();
        }
    }

    @Tool(description = "执行只读 SQL 查询（仅允许 SELECT/SHOW/DESC/EXPLAIN，任何增删改删库直接拒绝）。SELECT 不带 LIMIT 会自动补 LIMIT；单次最多返回200行。先 listDbTables + describeDbTable 确认表和字段，再写 SQL；大表查询务必带 WHERE 条件。")
    public DbQueryToolResult queryDb(DbQueryRequest request) {
        try {
            if (request == null || isBlank(request.getConnection()) || isBlank(request.getSql())) {
                return DbQueryToolResult.builder().success(false)
                        .errorMsg("请提供 connection 与 sql（只读 SELECT）").build();
            }
            DbQueryResult result = databaseQueryPort.query(request.getConnection().trim(), request.getSql());
            return DbQueryToolResult.builder()
                    .success(true)
                    .executedSql(result.getExecutedSql())
                    .columns(result.getColumns())
                    .rows(result.getRows())
                    .rowCount(result.getRowCount())
                    .truncated(result.getTruncated())
                    .costMs(result.getCostMs())
                    .build();
        } catch (Exception e) {
            log.warn("数据库查询异常: {}", e.getMessage());
            return DbQueryToolResult.builder().success(false).errorMsg("查询失败: " + e.getMessage()).build();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // ==================== 工具参数/返回 DTO ====================

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbTableListRequest {
        @JsonProperty(value = "connection")
        @JsonPropertyDescription("连接名（listDbConnections 返回的 connection，如 tongxin_hospital）")
        private String connection;

        @JsonProperty(value = "tableNameKeyword")
        @JsonPropertyDescription("表名关键字过滤（可选），如 order、patient、hpv")
        private String tableNameKeyword;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbDescribeRequest {
        @JsonProperty(value = "connection")
        @JsonPropertyDescription("连接名")
        private String connection;

        @JsonProperty(value = "tableName")
        @JsonPropertyDescription("表名（listDbTables 返回的 name）")
        private String tableName;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbQueryRequest {
        @JsonProperty(value = "connection")
        @JsonPropertyDescription("连接名")
        private String connection;

        @JsonProperty(value = "sql")
        @JsonPropertyDescription("只读 SQL，仅 SELECT/SHOW/DESC/EXPLAIN；SELECT 记得带 WHERE 和 LIMIT")
        private String sql;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbConnectionListResult {
        @JsonProperty(value = "success")
        private Boolean success;
        @JsonProperty(value = "errorMsg")
        private String errorMsg;
        @JsonProperty(value = "connections")
        @JsonPropertyDescription("可用数据库连接列表")
        private List<DbConnectionInfo> connections;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbConnectionInfo {
        @JsonProperty(value = "connection")
        @JsonPropertyDescription("连接名，作为其他工具的 connection 参数")
        private String connection;
        @JsonProperty(value = "label")
        @JsonPropertyDescription("连接说明（业务库/环境）")
        private String label;
        @JsonProperty(value = "database")
        @JsonPropertyDescription("库名")
        private String database;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbTableListResult {
        @JsonProperty(value = "success")
        private Boolean success;
        @JsonProperty(value = "errorMsg")
        private String errorMsg;
        @JsonProperty(value = "connection")
        private String connection;
        @JsonProperty(value = "returned")
        private Integer returned;
        @JsonProperty(value = "truncated")
        private Boolean truncated;
        @JsonProperty(value = "tables")
        @JsonPropertyDescription("表列表：name 表名 + comment 表注释")
        private List<DbTableInfo> tables;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbDescribeResult {
        @JsonProperty(value = "success")
        private Boolean success;
        @JsonProperty(value = "errorMsg")
        private String errorMsg;
        @JsonProperty(value = "connection")
        private String connection;
        @JsonProperty(value = "tableName")
        private String tableName;
        @JsonProperty(value = "createTable")
        @JsonPropertyDescription("SHOW CREATE TABLE 输出（字段/类型/注释/索引）")
        private String createTable;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DbQueryToolResult {
        @JsonProperty(value = "success")
        private Boolean success;
        @JsonProperty(value = "errorMsg")
        private String errorMsg;
        @JsonProperty(value = "executedSql")
        @JsonPropertyDescription("实际执行的 SQL（自动补 LIMIT 后）")
        private String executedSql;
        @JsonProperty(value = "columns")
        private List<String> columns;
        @JsonProperty(value = "rows")
        @JsonPropertyDescription("数据行，与 columns 顺序一致")
        private List<List<String>> rows;
        @JsonProperty(value = "rowCount")
        private Integer rowCount;
        @JsonProperty(value = "truncated")
        @JsonPropertyDescription("true=达到行数上限被截断，需要更多结果请加 WHERE/LIMIT 分页")
        private Boolean truncated;
        @JsonProperty(value = "costMs")
        private Long costMs;
    }
}
