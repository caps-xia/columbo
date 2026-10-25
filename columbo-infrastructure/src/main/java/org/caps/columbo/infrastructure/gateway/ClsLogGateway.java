package org.caps.columbo.infrastructure.gateway;

import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.DescribeLogContextRequest;
import com.tencentcloudapi.cls.v20201016.models.DescribeLogContextResponse;
import com.tencentcloudapi.cls.v20201016.models.DescribeTopicsRequest;
import com.tencentcloudapi.cls.v20201016.models.DescribeTopicsResponse;
import com.tencentcloudapi.cls.v20201016.models.Filter;
import com.tencentcloudapi.cls.v20201016.models.SearchLogRequest;
import com.tencentcloudapi.cls.v20201016.models.SearchLogResponse;
import com.tencentcloudapi.cls.v20201016.models.TopicInfo;
import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯云日志服务 CLS 网关（官方 SDK，TC3 签名内置），多站点：每站点独立 region/密钥各建一个 ClsClient
 * （南京/海南实测均在上海区 ap-shanghai，按日志集区分）。环境隔离按站点配置 envTagKey
 * （如海南 __TAG__.env 标签区分 product/qa 同主题混存），见 {@link #buildQuery}。
 */
@Slf4j
@Component
public class ClsLogGateway {

    /** SearchLog 单次返回原始日志上限（接口限制 1000） */
    public static final long MAX_LIMIT = 1000;

    private final ClsProperties clsProperties;

    /** 惰性构建：密钥为占位符时 Bean 仍可装配，查询时才报明确的配置错误；每站点一个客户端（region/密钥不同） */
    private final Map<String, ClsClient> clients = new ConcurrentHashMap<>();

    public ClsLogGateway(ClsProperties clsProperties) {
        this.clsProperties = clsProperties;
    }

    /**
     * 检索日志（无 SQL，返回原始日志 + Context 游标）。
     *
     * @param siteKey 站点标识（troubleshoot.cls.sites 的 key，决定 region/密钥）
     * @param query  CQL 检索条件（buildQuery 产物；站点配了 envTagKey 时必须含环境过滤）
     * @param context 上一页返回的游标，首页传 null；透传时不得变更其他参数
     */
    public SearchLogResponse searchLog(String siteKey, String topicId, long fromMs, long toMs, String query,
                                       Long limit, String sort, String context) {
        SearchLogRequest request = new SearchLogRequest();
        request.setTopicId(topicId);
        request.setFrom(fromMs);
        request.setTo(toMs);
        request.setQueryString(query);
        request.setQuerySyntax(1L);
        request.setUseNewAnalysis(true);
        request.setHighLight(false);
        if (limit != null) {
            request.setLimit(Math.max(1, Math.min(MAX_LIMIT, limit)));
        }
        if (StringUtils.hasText(sort)) {
            request.setSort(sort);
        }
        if (StringUtils.hasText(context)) {
            request.setContext(context);
        }
        log.info("腾讯云CLS查询 site={} topic={} range=[{} ~ {}] limit={} sort={} query={} ctx={}",
                siteKey, topicId, fromMs, toMs, request.getLimit(), sort, query, context == null ? "-" : "yes");
        try {
            SearchLogResponse response = client(siteKey).SearchLog(request);
            log.info("腾讯云CLS结果 site={} topic={} results={} listOver={}",
                    siteKey, topicId,
                    response.getResults() == null ? 0 : response.getResults().length,
                    response.getListOver());
            return response;
        } catch (TencentCloudSDKException e) {
            throw new AppException(ResponseCode.B001.getCode(), "腾讯云CLS接口调用失败:" + e.getMessage(), e);
        }
    }

    /** DescribeTopics 单页上限（接口限制 100），站点主题多于 100 时翻页拉取 */
    private static final long DESCRIBE_TOPICS_PAGE = 100;

    /**
     * 上下文检索（DescribeLogContext）：以 BTime+PkgId+PkgLogId 为锚点取前后各 N 条。
     * 注意 From/To 时间限定参数仅上海/弗吉尼亚/新加坡支持，这里不传（端口层在不支持地域降级模拟）。
     *
     * @param bTime 锚点日志时间，格式 yyyy-MM-dd HH:mm:ss.SSS（UTC+8）
     * @param query 检索条件（仅检索语法，不带 SQL；通常只放站点环境标签过滤）
     */
    public DescribeLogContextResponse describeLogContext(String siteKey, String topicId, String bTime,
                                                         String pkgId, long pkgLogId, long prevLogs,
                                                         long nextLogs, String query) {
        DescribeLogContextRequest request = new DescribeLogContextRequest();
        request.setTopicId(topicId);
        request.setBTime(bTime);
        request.setPkgId(pkgId);
        request.setPkgLogId(pkgLogId);
        request.setPrevLogs(prevLogs);
        request.setNextLogs(nextLogs);
        request.setQuery(query);
        log.info("腾讯云CLS上下文查询 site={} topic={} bTime={} pkg={}-{} prev={} next={}",
                siteKey, topicId, bTime, pkgId, pkgLogId, prevLogs, nextLogs);
        try {
            return client(siteKey).DescribeLogContext(request);
        } catch (TencentCloudSDKException e) {
            throw new AppException(ResponseCode.B001.getCode(), "腾讯云CLS上下文检索失败:" + e.getMessage(), e);
        }
    }

    /**
     * 拉取站点主题清单（DescribeTopics，按日志集过滤可选），返回 TopicName -> TopicId。
     * 供主题注册表自动同步：新应用自动补录、yml 占位 topicId 自动修正。
     */
    public Map<String, String> describeTopics(String siteKey, String logsetId) {
        try {
            Map<String, String> topics = new LinkedHashMap<>();
            long offset = 0;
            while (true) {
                DescribeTopicsRequest request = new DescribeTopicsRequest();
                request.setLimit(DESCRIBE_TOPICS_PAGE);
                request.setOffset(offset);
                if (StringUtils.hasText(logsetId)) {
                    Filter filter = new Filter();
                    filter.setKey("logsetId");
                    filter.setValues(new String[]{logsetId});
                    request.setFilters(new Filter[]{filter});
                }
                DescribeTopicsResponse response = client(siteKey).DescribeTopics(request);
                TopicInfo[] page = response.getTopics();
                if (page == null || page.length == 0) {
                    break;
                }
                for (TopicInfo topicInfo : page) {
                    topics.put(topicInfo.getTopicName(), topicInfo.getTopicId());
                }
                offset += page.length;
                if (response.getTotalCount() != null && offset >= response.getTotalCount()) {
                    break;
                }
            }
            log.info("腾讯云CLS主题同步 site={} logset={} 拉取主题数={}", siteKey, logsetId, topics.size());
            return topics;
        } catch (TencentCloudSDKException e) {
            throw new AppException(ResponseCode.B001.getCode(), "腾讯云CLS主题拉取失败:" + e.getMessage(), e);
        }
    }

    /**
     * 组装 CQL 检索条件：站点配置了 envTagKey 时环境过滤强制拼接（同主题混存多环境日志，漏拼会跨环境串数据），
     * traceId/级别/关键词按字段精确/短语匹配（键值索引字段：trace_id/log_level/message）。
     *
     * @param envTagKey 站点环境标签字段名（如 __TAG__.env）；空表示该站点环境已按主题隔离，不拼环境过滤
     */
    public static String buildQuery(String envTagKey, String env, String contextId, String logLevel, String keyword) {
        StringBuilder query = new StringBuilder();
        if (StringUtils.hasText(envTagKey)) {
            query.append(envTagKey).append(":\"").append(escapeCql(env)).append("\"");
        }
        if (StringUtils.hasText(contextId)) {
            appendAnd(query).append("trace_id:\"").append(escapeCql(contextId.trim())).append("\"");
        }
        if (StringUtils.hasText(logLevel)) {
            appendAnd(query).append("log_level:\"").append(escapeCql(logLevel.trim().toUpperCase())).append("\"");
        }
        if (StringUtils.hasText(keyword)) {
            appendAnd(query).append("message:\"").append(escapeCql(keyword.trim())).append("\"");
        }
        return query.toString();
    }

    private static StringBuilder appendAnd(StringBuilder query) {
        return query.length() == 0 ? query : query.append(" AND ");
    }

    /** CQL 引号值转义：值内的 \ 和 " 转义为字面量，避免检索语法注入改变查询语义 */
    private static String escapeCql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ClsClient client(String siteKey) {
        ClsProperties.Site site = clsProperties.siteOf(siteKey);
        if (site == null) {
            throw new AppException(ResponseCode.B002.getCode(),
                    "腾讯云CLS站点[" + siteKey + "]未配置，请在 troubleshoot.cls.sites 中维护");
        }
        return clients.computeIfAbsent(siteKey, k -> buildClient(siteKey, site));
    }

    private ClsClient buildClient(String siteKey, ClsProperties.Site site) {
        if (Boolean.FALSE.equals(clsProperties.getEnabled())) {
            throw new AppException(ResponseCode.B001.getCode(), "腾讯云CLS渠道未启用（troubleshoot.cls.enabled=false）");
        }
        if (!StringUtils.hasText(site.getRegion())) {
            throw new AppException(ResponseCode.B002.getCode(),
                    "腾讯云CLS站点[" + clsProperties.siteLabel(siteKey) + "]未配置 region（如 ap-shanghai）");
        }
        String secretId = clsProperties.effectiveSecretId(site);
        String secretKey = clsProperties.effectiveSecretKey(site);
        if (!StringUtils.hasText(secretId) || !StringUtils.hasText(secretKey)
                || secretId.startsWith(ClsProperties.PLACEHOLDER_PREFIX)
                || secretKey.startsWith(ClsProperties.PLACEHOLDER_PREFIX)) {
            throw new AppException(ResponseCode.B002.getCode(),
                    "腾讯云CLS站点[" + clsProperties.siteLabel(siteKey)
                            + "]密钥未配置：请在 troubleshoot.cls.sites." + siteKey
                            + ".secret-id/secret-key（或全局 troubleshoot.cls.secret-id/secret-key）"
                            + "填入子账号密钥（仅授 CLS 只读）后重启");
        }
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint(clsProperties.effectiveEndpoint(site));
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(httpProfile);
        return new ClsClient(new Credential(secretId, secretKey), site.getRegion(), clientProfile);
    }
}
