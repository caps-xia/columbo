package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLS 日志查询结果
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogResultVO {

    private Boolean success;

    /** 失败原因，成功时为空 */
    private String errorMsg;

    /** 本页返回条数 */
    private Integer returned;

    /** 日志列表，按时间倒序（最新在前） */
    private List<ClsLogEntryVO> logList;

    /** 是否已取完（false 时可能未取完） */
    private Boolean listOver;

    /** 下一页游标（ListOver=false 且单应用查询时返回；1小时有效） */
    private String nextPageContext;
}
