package org.caps.columbo.domain.troubleshoot.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 腾讯云 CLS 日志上下文查询参数：以某条日志为锚点取前后各 N 条（还原故障现场）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogContextQuery {

    /** 应用名列表（仅支持单应用，上下文按主题定位）；必填 */
    private List<String> appNames;

    /** 日志环境 product/qa；为空用站点默认 */
    private String env;

    /** 锚点日志 id（queryClsLog 结果里的 id，PkgId-PkgLogId）；必填 */
    private String logId;

    /** 锚点日志时间 yyyy-MM-dd HH:mm:ss.SSS（queryClsLog 结果里的 logTime）；必填 */
    private String logTime;

    /** 向前取的条数，默认 10 */
    private Integer prevCount;

    /** 向后取的条数，默认 10 */
    private Integer nextCount;
}
