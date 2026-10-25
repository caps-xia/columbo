package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CLS 单条日志（LogJson 字段解析结果：trace_id/log_level/message/class/timestamp + __TAG__ 元数据）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogEntryVO {

    /** 日志时间 yyyy-MM-dd HH:mm:ss.SSS */
    private String logTime;

    /** 日志级别 ERROR/WARN/INFO/DEBUG */
    private String level;

    /** 容器/Pod IP（__TAG__.pod_ip） */
    private String ip;

    /** Pod 名（__TAG__.pod_name），定位具体实例用 */
    private String podName;

    /** traceId（trace_id 字段） */
    private String contextId;

    /** 打日志的类（class 字段，如 c.l.h.h.utils.SkyLog） */
    private String className;

    /** 日志内容（超长截断，防撑爆 LLM 上下文） */
    private String msg;

    /** 日志环境（__TAG__.env） */
    private String env;

    /** 唯一标识（PkgId-PkgLogId） */
    private String id;
}
