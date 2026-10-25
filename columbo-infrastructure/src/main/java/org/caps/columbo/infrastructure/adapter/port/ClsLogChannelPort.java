package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.cls.v20201016.models.DescribeLogContextResponse;
import com.tencentcloudapi.cls.v20201016.models.LogContextInfo;
import com.tencentcloudapi.cls.v20201016.models.LogInfo;
import com.tencentcloudapi.cls.v20201016.models.SearchLogResponse;
import org.caps.columbo.domain.troubleshoot.adapter.port.IClsLogPort;
import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogContextQuery;
import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogQuery;
import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogContextResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogCountResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogEntryVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsTopicSyncResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.LogTime;
import org.caps.columbo.domain.troubleshoot.model.valobj.ResolvedTopic;
import org.caps.columbo.domain.troubleshoot.service.ClsTopicRegistry;
import org.caps.columbo.infrastructure.gateway.ClsLogGateway;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 腾讯云 CLS 日志渠道端口实现（多站点：南京/海南，实测均在上海区，按日志集区分）：
 * 单应用查询带 Context 游标翻页；多应用逐主题查询合并（时间倒序，不支持跨主题游标）。
 * 环境合法性按站点白名单校验；环境标签过滤按站点 envTagKey 配置（网关 buildQuery 拼接）。
 */
@Slf4j
@Component
public class ClsLogChannelPort implements IClsLogPort {

    private static final DateTimeFormatter TIME_FORMATTER = LocalDateTimeUtils.DATETIME_MILLI_FORMATTER;

    /** 单条日志 message 最大保留长度，防止撑爆 LLM 上下文 */
    private static final int MAX_MSG_LENGTH = 500;

    private static final String COUNT_SQL = " | select count(*) as cnt";

    /**
     * 多应用/多主题并行查询线程池：interface 调 shopstore 这类跨应用排查一次查 N 个主题，
     * 串行是 N 倍时延（单查 4-16s），并行后总耗时≈最慢一个。daemon 线程不阻碍停机。
     */
    private static final java.util.concurrent.ExecutorService TOPIC_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(8, runnable -> {
                Thread thread = new Thread(runnable, "cls-topic-query");
                thread.setDaemon(true);
                return thread;
            });

    /** 并行单主题查询超时（秒）：兜底防止单个慢主题拖住整体合并 */
    private static final long TOPIC_QUERY_TIMEOUT_SECONDS = 60;

    @Resource
    private ClsLogGateway clsLogGateway;

    @Resource
    private ClsProperties clsProperties;

    @Resource
    private ClsTopicRegistry clsTopicRegistry;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "tencent-cls-log";
    }

    @Override
    public String getChannelName() {
        return "腾讯云CLS日志";
    }

    @Override
    public String getChannelDesc() {
        return "查询腾讯云日志服务 CLS 日志，支持按应用名/中文别名、traceId、关键词、"
                + "时间范围、日志级别、product/qa 环境过滤，时间倒序返回；支持以某条日志为锚点查前后上下文。已接入应用："
                + clsTopicRegistry.knownTopicsSummary();
    }

    @Override
    public ClsLogResultVO queryClsLog(ClsLogQuery query) {
        List<ResolvedTopic> topics = clsTopicRegistry.resolve(query.getAppNames());
        long[] range = parseRange(query);
        Map<String, String> envBySite = resolveEnvBySite(topics, query.getEnv());
        Map<String, String> queryStringBySite = buildQueryBySite(topics, envBySite, query);
        int pageSize = query.getPageSize() == null ? 20 : query.getPageSize();

        // 多应用：逐主题并行查询后合并，游标仅单应用有效，多应用翻页让 LLM 缩小范围
        if (topics.size() > 1) {
            if (StringUtils.hasText(query.getNextPageContext())) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "多应用查询不支持翻页游标，请只传一个应用后使用 nextPageContext");
            }
            java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
            List<ClsLogEntryVO> merged = queryTopicsInParallel(topics, queryStringBySite, range, (long) pageSize, failures);
            merged.sort(Comparator.comparing(ClsLogEntryVO::getLogTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
            List<ClsLogEntryVO> logList = merged.stream().limit(pageSize).collect(Collectors.toList());
            boolean partialFailure = failures.get() > 0;
            return ClsLogResultVO.builder()
                    .success(true)
                    // 有主题失败时 listOver=false：避免 LLM 误以为"已取完"而漏掉失败应用的数据
                    .listOver(!partialFailure && merged.size() <= pageSize)
                    .errorMsg(partialFailure ? "部分应用日志查询失败（" + failures.get() + "/" + topics.size()
                            + "），结果可能不完整，可只查失败的应用重试" : null)
                    .returned(logList.size())
                    .logList(logList)
                    .build();
        }

        ResolvedTopic topic = topics.get(0);
        SearchLogResponse resp = clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(), range[0], range[1],
                queryStringBySite.get(topic.getSite()), (long) pageSize, "desc", query.getNextPageContext());
        List<ClsLogEntryVO> logList = mapEntries(resp);
        boolean listOver = resp.getListOver() == null || Boolean.TRUE.equals(resp.getListOver());
        return ClsLogResultVO.builder()
                .success(true)
                .returned(logList.size())
                .logList(logList)
                .listOver(listOver)
                .nextPageContext(listOver ? null : resp.getContext())
                .build();
    }

    @Override
    public ClsLogCountResultVO countClsLog(ClsLogQuery query) {
        List<ResolvedTopic> topics = clsTopicRegistry.resolve(query.getAppNames());
        long[] range = parseRange(query);
        Map<String, String> envBySite = resolveEnvBySite(topics, query.getEnv());
        Map<String, String> queryStringBySite = buildQueryBySite(topics, envBySite, query);

        List<ClsLogCountResultVO.TopicCount> topicCounts = countTopicsInParallel(topics, queryStringBySite, range);
        long total = topicCounts.stream().mapToLong(ClsLogCountResultVO.TopicCount::getCount).sum();
        return ClsLogCountResultVO.builder()
                .success(true)
                .count(total)
                .topicCounts(topicCounts)
                .build();
    }

    @Override
    public ClsLogContextResultVO queryClsLogContext(ClsLogContextQuery query) {
        List<ResolvedTopic> topics = clsTopicRegistry.resolve(query.getAppNames());
        if (topics.size() != 1) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "上下文检索仅支持单应用（上下文按主题内 PkgId 定位），请只传一个应用");
        }
        ResolvedTopic topic = topics.get(0);
        Map<String, String> envBySite = resolveEnvBySite(topics, query.getEnv());
        String[] anchor = parseLogId(query.getLogId());
        String bTime = LogTime.parse(query.getLogTime()).format();
        int prevCount = clamp(query.getPrevCount() == null ? 10 : query.getPrevCount(), 1, 50);
        int nextCount = clamp(query.getNextCount() == null ? 10 : query.getNextCount(), 1, 50);
        ClsProperties.Site site = clsProperties.siteOf(topic.getSite());
        String envTagKey = site == null ? null : site.getEnvTagKey();
        String envFilter = ClsLogGateway.buildQuery(envTagKey, envBySite.get(topic.getSite()), null, null, null);

        // 路径一：DescribeLogContext（南京/海南资源均在上海区，直接支持）；失败或空结果自动降级模拟
        try {
            DescribeLogContextResponse resp = clsLogGateway.describeLogContext(topic.getSite(), topic.getTopicId(),
                    bTime, anchor[0], Long.parseLong(anchor[1]), prevCount, nextCount, envFilter);
            LogContextInfo[] infos = resp.getLogContextInfos();
            if (infos != null && infos.length > 0) {
                return splitByAnchor(infos, anchor[0], anchor[1], prevCount, nextCount).toBuilder()
                        .appName(topic.getKey())
                        .source("api")
                        .prevOver(resp.getPrevOver())
                        .nextOver(resp.getNextOver())
                        .build();
            }
            log.info("CLS上下文API返回空，降级时间窗口模拟 site={} topic={}", topic.getSite(), topic.getKey());
        } catch (AppException e) {
            log.info("CLS上下文API不可用，降级时间窗口模拟 site={} topic={} err={}",
                    topic.getSite(), topic.getKey(), e.getInfo());
        }
        return simulateContext(topic, envFilter, bTime, prevCount, nextCount);
    }

    @Override
    public ClsTopicSyncResultVO syncClsTopics() {
        List<ClsTopicSyncResultVO.SiteSync> results = new ArrayList<>();
        boolean allOk = true;
        for (Map.Entry<String, ClsProperties.Site> entry : clsProperties.getSites().entrySet()) {
            String siteKey = entry.getKey();
            ClsTopicSyncResultVO.SiteSync.SiteSyncBuilder builder = ClsTopicSyncResultVO.SiteSync.builder()
                    .site(siteKey)
                    .label(clsProperties.siteLabel(siteKey));
            try {
                Map<String, String> remote = clsLogGateway.describeTopics(siteKey, entry.getValue().getLogsetId());
                long[] addedUpdated = clsTopicRegistry.applySync(siteKey, remote);
                results.add(builder.remoteCount((long) remote.size())
                        .added(addedUpdated[0]).updated(addedUpdated[1]).build());
            } catch (Exception e) {
                allOk = false;
                log.warn("CLS主题同步失败 site={} err={}", siteKey, e.getMessage());
                results.add(builder.errorMsg(e.getMessage()).build());
            }
        }
        return ClsTopicSyncResultVO.builder().success(allOk).sites(results).build();
    }

    // ============ 内部实现 ============

    /** 多主题并行检索并合并原始日志（保持各主题 limit 一致，调用方负责排序截断；failures 记失败数） */
    private List<ClsLogEntryVO> queryTopicsInParallel(List<ResolvedTopic> topics,
                                                      Map<String, String> queryStringBySite, long[] range, long pageSize,
                                                      java.util.concurrent.atomic.AtomicInteger failures) {
        List<java.util.concurrent.CompletableFuture<List<ClsLogEntryVO>>> futures = new ArrayList<>(topics.size());
        for (ResolvedTopic topic : topics) {
            futures.add(java.util.concurrent.CompletableFuture
                    .supplyAsync(() -> mapEntries(clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(),
                            range[0], range[1], queryStringBySite.get(topic.getSite()), pageSize, "desc", null)),
                            TOPIC_EXECUTOR)
                    .exceptionally(e -> {
                        // 单主题失败不拖垮整批：记 warn 返回空，合并继续
                        failures.incrementAndGet();
                        log.warn("CLS 多应用并行查询单主题失败 topic={} err={}", topic.getKey(),
                                rootMessage(e));
                        return new ArrayList<>();
                    }));
        }
        return joinAll(futures, topics);
    }

    /** 多主题并行探量：histogram 分桶优先（失败回退纯 count），按入参顺序返回各主题计数 */
    private List<ClsLogCountResultVO.TopicCount> countTopicsInParallel(List<ResolvedTopic> topics,
                                                                       Map<String, String> queryStringBySite,
                                                                       long[] range) {
        List<java.util.concurrent.CompletableFuture<ClsLogCountResultVO.TopicCount>> futures = new ArrayList<>(topics.size());
        for (ResolvedTopic topic : topics) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                String queryString = queryStringBySite.get(topic.getSite());
                List<ClsLogCountResultVO.Bucket> buckets = null;
                long count;
                try {
                    SearchLogResponse resp = clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(), range[0],
                            range[1], queryString + histogramSql(range[0], range[1]), null, null, null);
                    buckets = parseBuckets(resp);
                    count = buckets.stream().mapToLong(ClsLogCountResultVO.Bucket::getCount).sum();
                } catch (Exception e) {
                    log.warn("CLS histogram 统计失败，回退纯 count topic={} err={}", topic.getKey(), e.getMessage());
                    buckets = null;
                    SearchLogResponse resp = clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(), range[0],
                            range[1], queryString + COUNT_SQL, null, null, null);
                    count = parseCount(resp);
                }
                return ClsLogCountResultVO.TopicCount.builder()
                        .appName(topic.getKey())
                        .count(count)
                        .buckets(buckets == null || buckets.isEmpty() ? null : buckets)
                        .build();
            }, TOPIC_EXECUTOR).exceptionally(e -> ClsLogCountResultVO.TopicCount.builder()
                    .appName(topic.getKey())
                    .count(0L)
                    .build()));
        }
        List<ClsLogCountResultVO.TopicCount> counts = new ArrayList<>(topics.size());
        for (int i = 0; i < topics.size(); i++) {
            counts.add(joinOne(futures.get(i), topics.get(i).getKey()));
        }
        return counts;
    }

    /** 等待全部主题查询完成（带超时兜底），保持入参顺序 */
    private List<ClsLogEntryVO> joinAll(List<java.util.concurrent.CompletableFuture<List<ClsLogEntryVO>>> futures,
                                        List<ResolvedTopic> topics) {
        List<ClsLogEntryVO> merged = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            merged.addAll(joinOne(futures.get(i), topics.get(i).getKey()));
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private <T> T joinOne(java.util.concurrent.CompletableFuture<T> future, String topicKey) {
        try {
            return future.get(TOPIC_QUERY_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("CLS 并行查询超时/失败 topic={} err={}", topicKey, rootMessage(e));
            return (T) new ArrayList<>();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }


    /** 上下文模拟窗口：锚点前后各拉一个窗口，量大使 prev/next 不满时不会跨出太远 */
    private static final long CONTEXT_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * 上下文模拟兜底（DescribeLogContext 不可用/返回空时的降级；当前两站点均在支持地域，正常不会走到）：
     * 以锚点时间为界，两侧各做一次时间范围检索（前段倒序取最近 prevCount 条再正序化）。
     */
    private ClsLogContextResultVO simulateContext(ResolvedTopic topic, String envFilter, String bTime,
                                                  int prevCount, int nextCount) {
        long anchor = LogTime.parse(bTime).epochMilli();
        List<ClsLogEntryVO> prev = mapEntries(clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(),
                anchor - CONTEXT_WINDOW_MS, anchor, envFilter, (long) prevCount, "desc", null));
        Collections.reverse(prev);
        List<ClsLogEntryVO> next = mapEntries(clsLogGateway.searchLog(topic.getSite(), topic.getTopicId(),
                anchor, anchor + CONTEXT_WINDOW_MS, envFilter, (long) nextCount, "asc", null));
        return ClsLogContextResultVO.builder()
                .success(true)
                .appName(topic.getKey())
                .source("simulate")
                .prevLogs(prev)
                .nextLogs(next)
                .build();
    }

    /** DescribeLogContext 结果按锚点（PkgId-PkgLogId）切成前后两段；找不到锚点时前 prevCount 条当 prev、其余当 next */
    private ClsLogContextResultVO splitByAnchor(LogContextInfo[] infos, String pkgId, String pkgLogId,
                                                 int prevCount, int nextCount) {
        List<ClsLogEntryVO> all = new ArrayList<>(infos.length);
        int anchorIndex = -1;
        for (int i = 0; i < infos.length; i++) {
            LogContextInfo info = infos[i];
            if (pkgId.equals(info.getPkgId()) && pkgLogId.equals(String.valueOf(info.getPkgLogId()))) {
                anchorIndex = i;
            }
            all.add(mapContextEntry(info));
        }
        int split = anchorIndex >= 0 ? anchorIndex : Math.min(prevCount, all.size());
        List<ClsLogEntryVO> prev = all.subList(0, split);
        if (prev.size() > prevCount) {
            prev = prev.subList(prev.size() - prevCount, prev.size());
        }
        List<ClsLogEntryVO> next = all.subList(Math.min(split + 1, all.size()), all.size());
        if (next.size() > nextCount) {
            next = next.subList(0, nextCount);
        }
        return ClsLogContextResultVO.builder()
                .success(true)
                .prevLogs(new ArrayList<>(prev))
                .nextLogs(new ArrayList<>(next))
                .build();
    }

    /** 解析 queryClsLog 返回的 id（PkgId-PkgLogId，PkgId 自身含连字符，按最后一个 - 切分） */
    static String[] parseLogId(String logId) {
        if (StringUtils.hasText(logId)) {
            int idx = logId.trim().lastIndexOf('-');
            if (idx > 0 && idx < logId.trim().length() - 1) {
                String pkgId = logId.trim().substring(0, idx);
                String pkgLogId = logId.trim().substring(idx + 1);
                if (pkgLogId.chars().allMatch(Character::isDigit)) {
                    return new String[]{pkgId, pkgLogId};
                }
            }
        }
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                "logId 格式非法（应为 queryClsLog 结果里的 id，PkgId-PkgLogId），实际: " + logId);
    }

    /** 按查询时长选 histogram 桶宽（桶数远低于 API 上限） */
    static String histogramSql(long fromMs, long toMs) {
        long rangeMs = Math.max(toMs - fromMs, 60_000L);
        String interval;
        if (rangeMs <= 2 * 60 * 60 * 1000L) {
            interval = "5 minute";
        } else if (rangeMs <= 12 * 60 * 60 * 1000L) {
            interval = "30 minute";
        } else if (rangeMs <= 2 * 24 * 60 * 60 * 1000L) {
            interval = "1 hour";
        } else if (rangeMs <= 7 * 24 * 60 * 60 * 1000L) {
            interval = "6 hour";
        } else {
            interval = "1 day";
        }
        return " | select histogram(cast(__TIMESTAMP__ as timestamp), interval " + interval
                + ") as t, count(*) as cnt group by t order by t";
    }

    /** 解析 histogram 分析行（{"t":"2026-09-18 12:00:00.000","cnt":5}），异常/无行交给调用方回退 */
    private List<ClsLogCountResultVO.Bucket> parseBuckets(SearchLogResponse resp) throws java.io.IOException {
        String[] records = resp.getAnalysisRecords();
        if (records == null || records.length == 0) {
            return List.of();
        }
        List<ClsLogCountResultVO.Bucket> buckets = new ArrayList<>(records.length);
        for (String record : records) {
            JsonNode node = objectMapper.readTree(record);
            String time = node.path("t").asText(null);
            long count = node.path("cnt").asLong(0);
            if (time == null) {
                continue;
            }
            buckets.add(ClsLogCountResultVO.Bucket.builder().time(time).count(count).build());
        }
        return buckets;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 按站点解析日志环境：为空用站点默认（回退全局默认），再按站点白名单校验（多站点各自校验） */
    private Map<String, String> resolveEnvBySite(List<ResolvedTopic> topics, String rawEnv) {
        String normalized = StringUtils.hasText(rawEnv) ? rawEnv.trim().toLowerCase() : null;
        Set<String> siteKeys = topics.stream().map(ResolvedTopic::getSite)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> envBySite = new HashMap<>();
        for (String siteKey : siteKeys) {
            ClsProperties.Site site = clsProperties.siteOf(siteKey);
            String env = normalized != null ? normalized : clsProperties.effectiveEnv(site);
            List<String> allowed = clsProperties.effectiveEnvs(site);
            if (!allowed.contains(env)) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "站点[" + clsProperties.siteLabel(siteKey) + "]仅支持日志环境 " + allowed
                                + "，实际: " + (rawEnv == null ? env : rawEnv));
            }
            envBySite.put(siteKey, env);
        }
        return envBySite;
    }

    /** 按站点组装检索条件（环境标签过滤是否拼、拼什么键均由站点配置决定） */
    private Map<String, String> buildQueryBySite(List<ResolvedTopic> topics, Map<String, String> envBySite,
                                                 ClsLogQuery query) {
        Set<String> siteKeys = topics.stream().map(ResolvedTopic::getSite)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> queryStringBySite = new HashMap<>();
        for (String siteKey : siteKeys) {
            ClsProperties.Site site = clsProperties.siteOf(siteKey);
            String envTagKey = site == null ? null : site.getEnvTagKey();
            queryStringBySite.put(siteKey, ClsLogGateway.buildQuery(envTagKey, envBySite.get(siteKey),
                    query.getContextId(), query.getLogLevel(), query.getKeyword()));
        }
        return queryStringBySite;
    }

    private long[] parseRange(ClsLogQuery query) {
        try {
            return new long[]{LogTime.parse(query.getBeginTime()).epochMilli(),
                    LogTime.parse(query.getEndTime()).epochMilli()};
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "时间参数解析失败，期望 yyyy-MM-dd HH:mm:ss.SSS，begin=" + query.getBeginTime() + " end=" + query.getEndTime());
        }
    }

    private List<ClsLogEntryVO> mapEntries(SearchLogResponse resp) {
        LogInfo[] results = resp.getResults();
        if (results == null || results.length == 0) {
            return new ArrayList<>();
        }
        List<ClsLogEntryVO> entries = new ArrayList<>(results.length);
        for (LogInfo item : results) {
            entries.add(mapEntry(item));
        }
        return entries;
    }

    private ClsLogEntryVO mapEntry(LogInfo item) {
        return entryFrom(item.getPkgId() + "-" + item.getPkgLogId(), item.getLogJson(),
                item.getTime(), item.getLogJson());
    }

    /** DescribeLogContext 的条目：JSON 日志在 Content（解析失败兜底 RawLog），BTime 为毫秒时间戳 */
    private ClsLogEntryVO mapContextEntry(LogContextInfo item) {
        return entryFrom(item.getPkgId() + "-" + item.getPkgLogId(), item.getContent(), item.getBTime(),
                StringUtils.hasText(item.getRawLog()) ? item.getRawLog() : item.getContent());
    }

    /** 单条日志统一映射：JSON 结构取字段（trace_id/log_level/message/class/__TAG__），异常降级原文 */
    private ClsLogEntryVO entryFrom(String id, String json, Long timeMs, String raw) {
        ClsLogEntryVO.ClsLogEntryVOBuilder builder = ClsLogEntryVO.builder().id(id);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tag = root.path("__TAG__");
            return builder
                    .logTime(textOrNull(root, "timestamp"))
                    .level(textOrNull(root, "log_level"))
                    .contextId(textOrNull(root, "trace_id"))
                    .className(textOrNull(root, "class"))
                    .msg(truncate(textOrNull(root, "message")))
                    .env(textOrNull(tag, "env"))
                    .ip(textOrNull(tag, "pod_ip"))
                    .podName(textOrNull(tag, "pod_name"))
                    .build();
        } catch (Exception e) {
            // 字段结构变化/非 JSON 日志时兜底展示原文，不丢数据
            log.warn("CLS LogJson 解析失败，降级原文展示 id={}", id);
            return builder
                    .msg(truncate(raw))
                    .logTime(formatTime(timeMs))
                    .build();
        }
    }

    private long parseCount(SearchLogResponse resp) {
        try {
            String[] records = resp.getAnalysisRecords();
            if (records != null && records.length > 0) {
                JsonNode node = objectMapper.readTree(records[0]);
                return node.path("cnt").asLong(0);
            }
        } catch (Exception e) {
            log.warn("CLS count 结果解析失败 analysis={}", (Object) resp.getAnalysisRecords());
        }
        return 0;
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String truncate(String msg) {
        if (msg == null || msg.length() <= MAX_MSG_LENGTH) {
            return msg;
        }
        return msg.substring(0, MAX_MSG_LENGTH) + "...";
    }

    private static String formatTime(Long epochMilli) {
        if (epochMilli == null) {
            return null;
        }
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault())
                .format(TIME_FORMATTER);
    }
    @jakarta.annotation.PreDestroy
    public void shutdownTopicExecutor() {
        TOPIC_EXECUTOR.shutdownNow();
    }
}
