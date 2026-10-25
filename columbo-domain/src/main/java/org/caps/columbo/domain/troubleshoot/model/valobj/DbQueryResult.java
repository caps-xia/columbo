package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 只读 SQL 查询结果（行数与单元格均已截断） */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DbQueryResult {

    /** 实际执行的 SQL（自动补 LIMIT 后的） */
    private String executedSql;

    /** 列名 */
    private List<String> columns;

    /** 数据行（与 columns 顺序一致，值已转字符串并截断） */
    private List<List<String>> rows;

    /** 返回行数 */
    private Integer rowCount;

    /** 是否因达到 maxRows 截断 */
    private Boolean truncated;

    /** 执行耗时（毫秒） */
    private Long costMs;
}
