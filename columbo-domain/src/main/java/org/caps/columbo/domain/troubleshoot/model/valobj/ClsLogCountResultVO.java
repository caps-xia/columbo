package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLS 日志探量结果（按主题 SQL count，无明细）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsLogCountResultVO {

    private Boolean success;

    /** 失败原因，成功时为空 */
    private String errorMsg;

    /** 命中日志总条数（各主题之和） */
    private Long count;

    /** 各主题命中明细 */
    private List<TopicCount> topicCounts;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TopicCount {

        /** 应用名（主题名） */
        private String appName;

        /** 该主题命中条数（分桶求和；分桶解析失败时为纯 count 结果） */
        private Long count;

        /** 时间分桶分布（时间正序），帮助判断问题集中在哪个时段；无数据/解析失败为空 */
        private List<Bucket> buckets;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Bucket {

        /** 桶起始时间 yyyy-MM-dd HH:mm:ss */
        private String time;

        /** 该桶命中条数（大范围统计可能为采样估算值） */
        private Long count;
    }
}
