package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLS 日志上下文查询结果（锚点日志前后各 N 条，按时间正序）
 */
@Data
@Builder(toBuilder = true)
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogContextResultVO {

    private Boolean success;

    /** 失败原因，成功时为空 */
    private String errorMsg;

    /** 应用名（主题名） */
    private String appName;

    /** 实现路径：api=DescribeLogContext（海南 ap-shanghai 支持）；simulate=SearchLog 时间窗口模拟（南京等不支持上下文API的地域自动降级） */
    private String source;

    /** 前面没有更多日志（已到主题头部，仅 api 路径返回） */
    private Boolean prevOver;

    /** 后面没有更多日志（已到主题尾部，仅 api 路径返回） */
    private Boolean nextOver;

    /** 锚点之前的日志（时间正序，最靠近锚点的在末尾） */
    private List<ClsLogEntryVO> prevLogs;

    /** 锚点之后的日志（时间正序） */
    private List<ClsLogEntryVO> nextLogs;
}
