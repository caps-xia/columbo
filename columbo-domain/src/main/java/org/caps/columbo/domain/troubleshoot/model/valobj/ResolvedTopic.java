package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CLS 注册表中解析出的主题（站点 + 应用名 -> topicId）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResolvedTopic {

    /** 所属站点标识（troubleshoot.cls.sites 的 key，如 hainan/nanjing，决定 region/密钥/环境规则） */
    private String site;

    /** 注册表 key（应用名/主题名，如 hnhy.java.hospital.admin.api.web） */
    private String key;

    /** 应用中文名 */
    private String name;

    /** CLS 日志主题 ID */
    private String topicId;
}
