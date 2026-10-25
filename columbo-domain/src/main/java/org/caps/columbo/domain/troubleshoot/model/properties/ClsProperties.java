package org.caps.columbo.domain.troubleshoot.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯云日志服务 CLS 渠道配置（多站点：南京/海南等，均在 ap-shanghai 上海区，按日志集区分）。
 * 每站点独立 region/密钥/主题表；主题名即应用名，环境隔离默认靠日志内 __TAG__.env 标签（可按站点关闭）。
 */
@Data
@ConfigurationProperties(prefix = "troubleshoot.cls", ignoreInvalidFields = true)
public class ClsProperties {

    /** 占位符前缀：密钥/主题ID 未配置时以此开头，查询时给出明确的配置错误提示 */
    public static final String PLACEHOLDER_PREFIX = "please-replace";

    /** 是否启用（false 时渠道不出现在工具列表，查询直接报未启用） */
    private Boolean enabled = true;

    /** 全局兜底密钥：站点未单独配置时使用（多站点同一腾讯云子账号时只需配一遍） */
    private String secretId = "please-replace-with-cls-secret-id";

    /** 全局兜底 SecretKey */
    private String secretKey = "please-replace-with-cls-secret-key";

    /** API 接入域名，一般不用改 */
    private String endpoint = "cls.tencentcloudapi.com";

    /** 站点未配置 env 时的全局默认日志环境 */
    private String env = "product";

    /** 站点注册表：key=站点标识（如 hainan/nanjing）-> 站点配置（region/密钥/主题表） */
    private Map<String, Site> sites = new LinkedHashMap<>();

    @Data
    public static class Site {

        /** 站点中文名（渠道描述/错误提示用），如: 海南、南京 */
        private String label;

        /** 地域代码（南京/海南互医实测均在上海区 ap-shanghai，按日志集区分；新站点以 DescribeLogsets 实测为准） */
        private String region;

        /** 所属日志集 ID（可选）：主题自动同步时按日志集过滤 DescribeTopics，避免拉到同账号其他日志集的主题 */
        private String logsetId;

        /** 站点独立子账号 SecretId（各站点可归属不同腾讯云账号）；未配置回退全局 */
        private String secretId;

        /** 站点独立子账号 SecretKey */
        private String secretKey;

        /** 为空回退全局 endpoint */
        private String endpoint;

        /** 该站点默认日志环境（为空回退全局 env） */
        private String env;

        /** 该站点支持的日志环境白名单（端口按此校验），默认 product/qa */
        private List<String> envs;

        /**
         * 环境标签字段名（默认 __TAG__.env）：product/qa 同主题混存的站点必须保留，
         * 网关会把该标签过滤强拼进检索条件防止跨环境串数据；
         * 各环境独立主题、无此标签的站点配空字符串关闭过滤。
         */
        private String envTagKey = "__TAG__.env";

        /**
         * 主题注册表：key=应用名（如 hnhy.java.hospital.admin.api.web，即 TopicName）-> 主题配置。
         * name/aliases 供按中文名/别名检索，topicId 从 CLS 控制台「日志集管理」获取。
         */
        private Map<String, Topic> topics = new LinkedHashMap<>();
    }

    @Data
    public static class Topic {

        /** 应用中文名，如: 订单后台管理接口系统 */
        private String name;

        /** 中文/英文别名，供按口语检索 */
        private List<String> aliases;

        /** CLS 日志主题 ID（uuid） */
        private String topicId;
    }

    // ============ 生效值取值（站点配置为空回退全局） ============

    /** 站点配置；不存在返回 null（调用方给明确的配置错误） */
    public Site siteOf(String siteKey) {
        return siteKey == null ? null : sites.get(siteKey);
    }

    public String effectiveSecretId(Site site) {
        return site != null && StringUtils.hasText(site.getSecretId()) ? site.getSecretId() : secretId;
    }

    public String effectiveSecretKey(Site site) {
        return site != null && StringUtils.hasText(site.getSecretKey()) ? site.getSecretKey() : secretKey;
    }

    public String effectiveEndpoint(Site site) {
        return site != null && StringUtils.hasText(site.getEndpoint()) ? site.getEndpoint() : endpoint;
    }

    public String effectiveEnv(Site site) {
        String value = site != null && StringUtils.hasText(site.getEnv()) ? site.getEnv() : env;
        return StringUtils.hasText(value) ? value : "product";
    }

    /** 站点环境白名单：未配置默认 product/qa（海南/南京现状均无 stage） */
    public List<String> effectiveEnvs(Site site) {
        if (site != null && site.getEnvs() != null && !site.getEnvs().isEmpty()) {
            return site.getEnvs();
        }
        return List.of("product", "qa");
    }

    /** 站点显示名：配置了 label 用 label，否则用站点标识 */
    public String siteLabel(String siteKey) {
        Site site = siteOf(siteKey);
        return site != null && StringUtils.hasText(site.getLabel()) ? site.getLabel() : siteKey;
    }
}
