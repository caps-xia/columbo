package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CLS 主题注册表同步结果（DescribeTopics 拉取线上主题，与 yml 注册表合并）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ClsTopicSyncResultVO {

    private Boolean success;

    /** 各站点同步明细 */
    private List<SiteSync> sites;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SiteSync {

        /** 站点标识（troubleshoot.cls.sites 的 key） */
        private String site;

        /** 站点中文名 */
        private String label;

        /** 线上拉到的主题数 */
        private Long remoteCount;

        /** 新增主题数（yml 未登记，自动补进注册表） */
        private Long added;

        /** 修正主题数（含 yml 占位 topicId 被线上真实值替换） */
        private Long updated;

        /** 该站点同步失败原因，成功为空 */
        private String errorMsg;
    }
}
