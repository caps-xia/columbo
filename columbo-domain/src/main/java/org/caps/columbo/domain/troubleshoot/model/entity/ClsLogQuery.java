package org.caps.columbo.domain.troubleshoot.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 腾讯云 CLS 日志查询参数（时间格式 yyyy-MM-dd HH:mm:ss.SSS）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogQuery {

    /** 应用名列表（如 hnhy.java.hospital.admin.api.web，或中文别名，由注册表换算）；必填 */
    private List<String> appNames;

    /** 日志环境（默认 product/qa，按站点白名单校验）；为空用站点默认（troubleshoot.cls.sites.*.env） */
    private String env;

    /** 跟踪ID/traceId，映射为 trace_id 字段精确检索 */
    private String contextId;

    /** 日志内容关键词，映射为 message 字段短语检索 */
    private String keyword;

    /** 开始时间 yyyy-MM-dd HH:mm:ss.SSS */
    private String beginTime;

    /** 结束时间 yyyy-MM-dd HH:mm:ss.SSS */
    private String endTime;

    /** 日志级别 ERROR/WARN/INFO/DEBUG（CLS 实际值为大写） */
    private String logLevel;

    /** 返回条数 1-1000 */
    private Integer pageSize;

    /** 翻页游标：上一页结果返回的 nextPageContext（仅单应用查询支持翻页） */
    private String nextPageContext;
}
