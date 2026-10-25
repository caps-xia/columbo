package org.caps.columbo.domain.troubleshoot.adapter.port;

import org.caps.columbo.domain.troubleshoot.model.valobj.DbQueryResult;
import org.caps.columbo.domain.troubleshoot.model.valobj.DbTableInfo;

import java.util.List;

/**
 * 数据库只读查询端口：实现走 JDBC（infrastructure），
 * 强制 SELECT/SHOW/DESC/EXPLAIN 白名单、自动补 LIMIT、语句超时与行数上限。
 */
public interface IDatabaseQueryPort {

    /** 列出库中的表（tableNameKeyword 可选过滤） */
    List<DbTableInfo> listTables(String connectionName, String tableNameKeyword);

    /** 表结构：SHOW CREATE TABLE 输出（含索引/注释，排查字段含义用） */
    String describeTable(String connectionName, String tableName);

    /** 执行只读 SQL（仅 SELECT/SHOW/DESC/EXPLAIN；无 LIMIT 的 SELECT 自动补 LIMIT） */
    DbQueryResult query(String connectionName, String sql);
}
