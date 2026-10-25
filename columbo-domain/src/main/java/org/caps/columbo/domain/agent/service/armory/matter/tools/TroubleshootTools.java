package org.caps.columbo.domain.agent.service.armory.matter.tools;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.caps.columbo.domain.troubleshoot.adapter.port.IClsLogPort;
import org.caps.columbo.domain.troubleshoot.adapter.port.ITroubleshootChannel;
import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogContextQuery;
import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogQuery;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogContextResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogCountResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogEntryVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.LogEnv;
import org.caps.columbo.domain.troubleshoot.model.valobj.LogTime;
import org.caps.columbo.domain.troubleshoot.model.valobj.TimeWindow;
import org.caps.columbo.types.exception.AppException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 线上问题排查工具集，通过 @Tool 暴露给 LLM。
 * 新增排查渠道：实现 ITroubleshootChannel 后在本类增加对应 @Tool 查询方法。
 */
@Slf4j
@Service
public class TroubleshootTools {

    private static final DateTimeFormatter TIME_FORMATTER = LocalDateTimeUtils.DATETIME_MILLI_FORMATTER;

    /** 日志查询单页上限 */
    private static final int MAX_PAGE_SIZE = 500;

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Resource
    private IClsLogPort clsLogPort;

    /** Spring 注入所有排查渠道实现，用于渠道自动发现 */
    @Resource
    private List<ITroubleshootChannel> troubleshootChannels;

    @Tool(description = "查询腾讯云CLS日志（日志服务 Cloud Log Service，多站点多应用）。支持按应用名（TopicName，如 myapp.java.order.web）或中文别名（如: 订单服务）、跟踪ID(traceId/contextId)、关键词、时间范围、日志级别组合查询，时间倒序返回。查询前建议先用 countClsLog 探量。日志环境默认生产(product)，用户说 qa/QA环境/测试环境时传 logEnv=qa。多应用查询不支持翻页，翻页时只传一个应用；翻页游标1小时过期、单应用最多连续翻1万条，翻页报错或拿不到下一页时改用更精确条件缩小范围重查而不是反复重试。密钥未配置时返回明确提示，如实转告用户。")
    public ClsLogQueryResult queryClsLog(ClsLogQueryRequest request) {
        try {
            if (request == null) {
                request = new ClsLogQueryRequest();
            }
            if (isBlankList(request.getAppNames())) {
                return ClsLogQueryResult.builder()
                        .success(false)
                        .errorMsg("请提供 appNames（应用名如 myapp.java.order.web，或 troubleshoot.cls.sites.<site>.topics 里配置的中文别名）")
                        .build();
            }
            if (blankToNull(request.getKeyword()) == null && blankToNull(request.getContextId()) == null
                    && blankToNull(request.getLogLevel()) == null) {
                return ClsLogQueryResult.builder()
                        .success(false)
                        .errorMsg("请提供 contextId(跟踪ID)、keyword(关键词) 或 logLevel(日志级别) 至少一项，避免无过滤条件全量拉取日志")
                        .build();
            }
            ClsLogQuery query = toClsQuery(request);
            ClsLogResultVO resultVO = clsLogPort.queryClsLog(query);
            return ClsLogQueryResult.builder()
                    .success(resultVO.getSuccess())
                    .errorMsg(resultVO.getErrorMsg())
                    .returned(resultVO.getReturned())
                    .logList(resultVO.getLogList())
                    .listOver(resultVO.getListOver())
                    .nextPageContext(resultVO.getNextPageContext())
                    .build();
        } catch (AppException e) {
            log.warn("CLS日志查询业务异常 code:{} info:{}", e.getCode(), e.getInfo());
            return ClsLogQueryResult.builder().success(false).errorMsg(withRetryGuidance(e.getInfo())).build();
        } catch (Exception e) {
            log.error("CLS日志查询异常", e);
            return ClsLogQueryResult.builder().success(false).errorMsg("CLS日志查询异常:" + e.getMessage()).build();
        }
    }

    @Tool(description = "探量：只查询腾讯云CLS日志命中条数，不返回日志记录、消耗极小。正式查询前先调用它预估数据量：count为0时换关键词或调整时间范围；count很大(如上万)时先告知用户预计数据量。按应用返回各自命中明细；大范围统计CLS可能自动采样，count为估算值，量级判断够用。日志环境 product(默认)/qa 由 logEnv 参数指定，与正式查询保持一致。")
    public ClsLogCountResult countClsLog(ClsLogCountRequest request) {
        try {
            if (request == null) {
                request = new ClsLogCountRequest();
            }
            if (isBlankList(request.getAppNames())) {
                return ClsLogCountResult.builder()
                        .success(false)
                        .errorMsg("请提供 appNames（应用名如 myapp.java.order.web，或配置的中文别名）")
                        .build();
            }
            ClsLogQuery query = ClsLogQuery.builder()
                    .appNames(request.getAppNames())
                    .env(normalizeClsEnv(request.getLogEnv()))
                    .contextId(blankToNull(request.getContextId()))
                    .keyword(blankToNull(request.getKeyword()))
                    .logLevel(blankToNull(request.getLogLevel()))
                    .beginTime(resolveBeginTime(request.getTimeWindow(), request.getBeginTime()))
                    .endTime(resolveEndTime(request.getTimeWindow(), request.getEndTime()))
                    .build();
            ClsLogCountResultVO resultVO = clsLogPort.countClsLog(query);
            return ClsLogCountResult.builder()
                    .success(resultVO.getSuccess())
                    .errorMsg(resultVO.getErrorMsg())
                    .count(resultVO.getCount())
                    .topicCounts(toTopicCounts(resultVO.getTopicCounts()))
                    .build();
        } catch (AppException e) {
            log.warn("CLS日志探量业务异常 code:{} info:{}", e.getCode(), e.getInfo());
            return ClsLogCountResult.builder().success(false).errorMsg(withRetryGuidance(e.getInfo())).build();
        } catch (Exception e) {
            log.error("CLS日志探量异常", e);
            return ClsLogCountResult.builder().success(false).errorMsg("CLS日志探量异常:" + e.getMessage()).build();
        }
    }

    @Tool(description = "上下文检索：以某条日志为锚点取前后各 N 条（还原故障现场，看异常前后发生了什么）。锚点信息来自 queryClsLog 的结果：传该条的 id（logId）和 logTime，应用与 logEnv 与原查询保持一致，仅支持单应用。官方上下文接口直接可用（个别地域不支持时自动降级时间窗口模拟，source 字段标明路径）。")
    public ClsLogContextResult queryClsLogContext(ClsLogContextRequest request) {
        try {
            if (request == null || isBlankList(request.getAppNames())) {
                return ClsLogContextResult.builder()
                        .success(false)
                        .errorMsg("请提供 appNames（仅一个应用，如 myapp.java.order.web 或配置的中文别名）")
                        .build();
            }
            if (blankToNull(request.getLogId()) == null || blankToNull(request.getLogTime()) == null) {
                return ClsLogContextResult.builder()
                        .success(false)
                        .errorMsg("请提供锚点日志的 logId 和 logTime（来自 queryClsLog 结果里的 id 与 logTime 字段）")
                        .build();
            }
            ClsLogContextQuery query = ClsLogContextQuery.builder()
                    .appNames(request.getAppNames())
                    .env(normalizeClsEnv(request.getLogEnv()))
                    .logId(blankToNull(request.getLogId()))
                    .logTime(blankToNull(request.getLogTime()))
                    .prevCount(request.getPrevCount())
                    .nextCount(request.getNextCount())
                    .build();
            ClsLogContextResultVO resultVO = clsLogPort.queryClsLogContext(query);
            return ClsLogContextResult.builder()
                    .success(resultVO.getSuccess())
                    .errorMsg(resultVO.getErrorMsg())
                    .appName(resultVO.getAppName())
                    .source(resultVO.getSource())
                    .prevOver(resultVO.getPrevOver())
                    .nextOver(resultVO.getNextOver())
                    .prevLogs(resultVO.getPrevLogs())
                    .nextLogs(resultVO.getNextLogs())
                    .build();
        } catch (AppException e) {
            log.warn("CLS日志上下文业务异常 code:{} info:{}", e.getCode(), e.getInfo());
            return ClsLogContextResult.builder().success(false).errorMsg(withRetryGuidance(e.getInfo())).build();
        } catch (Exception e) {
            log.error("CLS日志上下文异常", e);
            return ClsLogContextResult.builder().success(false).errorMsg("CLS日志上下文查询异常:" + e.getMessage()).build();
        }
    }

    @Tool(description = "列出当前可用的问题排查数据渠道及各自能力说明。排查开始不确定用什么渠道、或当前渠道查不到信息时调用。")
    public ChannelListResult listTroubleshootChannels() {
        List<ChannelInfo> channels = new ArrayList<>();
        for (ITroubleshootChannel channel : troubleshootChannels) {
            channels.add(ChannelInfo.builder()
                    .channelType(channel.getChannelType())
                    .channelName(channel.getChannelName())
                    .channelDesc(channel.getChannelDesc())
                    .build());
        }
        ChannelListResult result = new ChannelListResult();
        result.setChannels(channels);
        return result;
    }

    private static ClsLogQuery toClsQuery(ClsLogQueryRequest request) {
        return ClsLogQuery.builder()
                .appNames(request.getAppNames())
                .env(normalizeClsEnv(request.getLogEnv()))
                .contextId(blankToNull(request.getContextId()))
                .keyword(blankToNull(request.getKeyword()))
                .logLevel(blankToNull(request.getLogLevel()))
                .beginTime(resolveBeginTime(request.getTimeWindow(), request.getBeginTime()))
                .endTime(resolveEndTime(request.getTimeWindow(), request.getEndTime()))
                .pageSize(clampPageSize(request.getPageSize()))
                .nextPageContext(blankToNull(request.getNextPageContext()))
                .build();
    }

    /** CLS 日志环境归一：仅大小写/空白归一，合法性由端口按站点白名单校验（详见 LogEnv DP） */
    static String normalizeClsEnv(String logEnv) {
        return LogEnv.normalizeClsValue(logEnv);
    }

    private static List<ClsLogCountResult.TopicCount> toTopicCounts(
            List<ClsLogCountResultVO.TopicCount> topicCounts) {
        if (topicCounts == null) {
            return null;
        }
        List<ClsLogCountResult.TopicCount> mapped = new ArrayList<>(topicCounts.size());
        for (ClsLogCountResultVO.TopicCount topicCount : topicCounts) {
            List<ClsLogCountResult.Bucket> buckets = null;
            if (topicCount.getBuckets() != null) {
                buckets = topicCount.getBuckets().stream()
                        .map(b -> ClsLogCountResult.Bucket.builder().time(b.getTime()).count(b.getCount()).build())
                        .collect(Collectors.toList());
            }
            mapped.add(ClsLogCountResult.TopicCount.builder()
                    .appName(topicCount.getAppName())
                    .count(topicCount.getCount())
                    .buckets(buckets)
                    .build());
        }
        return mapped;
    }

    static String normalizeTime(String time, LocalDateTime defaultTime) {
        return LogTime.parseOrDefault(time, defaultTime).format();
    }

    /**
     * 相对时间窗优先解析开始时间：模型不感知服务器当前时钟，编造具体日期会差一年/一天，
     * 因此相对表述（最近N小时等）由服务端按真实时间换算。
     */
    static String resolveBeginTime(String timeWindow, String beginTime) {
        if (blankToNull(timeWindow) != null) {
            return TimeWindow.parse(timeWindow).beginBefore(LocalDateTime.now()).format();
        }
        return normalizeTime(beginTime, LocalDate.now().atStartOfDay());
    }

    static String resolveEndTime(String timeWindow, String endTime) {
        if (blankToNull(timeWindow) != null) {
            return LogTime.of(LocalDateTime.now()).format();
        }
        return normalizeTime(endTime, LocalDateTime.now());
    }

    /** 解析相对时间窗：详见 TimeWindow DP（30m/1h/12h/1d，兼容中英文单位） */
    static java.time.Duration parseTimeWindow(String window) {
        return TimeWindow.parse(window).duration();
    }

    static Integer clampPageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static boolean isBlankList(List<String> list) {
        return list == null || list.stream().allMatch(item -> item == null || item.isBlank());
    }

    /**
     * 业务错误信息附加处置引导：凭证/环境类错误明确"重试无效"，避免模型读到失败后
     * 原样重发——线上实证：token 过期后模型连续 3 轮发出完全相同的查询被熔断，
     * 错误文本里的"请检查参数后重试"类措辞会被弱模型当成重试指令。
     */
    static String withRetryGuidance(String errorMsg) {
        if (errorMsg == null) {
            return null;
        }
        if (!isCredentialError(errorMsg)) {
            return errorMsg;
        }
        return errorMsg + "【处置】该错误为凭证/环境问题，重试或修改参数均无法解决，"
                + "请勿再次调用本工具，直接向用户说明现状并建议更换凭证或联系环境负责人。";
    }

    /** 凭证/环境类错误识别：重新登录/access_token/无权限/401/403 等关键词（覆盖密钥失效、token 过期等场景） */
    static boolean isCredentialError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("重新登录") || lower.contains("access_token") || lower.contains("无权限")
                || lower.contains("未授权") || lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("http 401") || lower.contains("http 403");
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogQueryRequest {
        @JsonProperty(value = "appNames")
        @JsonPropertyDescription("应用名列表（如: [\"myapp.java.order.web\"]）或中文别名（如: [\"订单服务\"]），系统自动映射为站点与主题ID；resolveRouting 返回的 clsAppName 可直接用")
        private List<String> appNames;

        @JsonProperty(value = "logEnv")
        @JsonPropertyDescription("日志环境: product(生产，默认)/qa(测试)。用户说 qa/QA环境/测试环境时传 qa，说生产或不提时省略（CLS 站点默认无 stage，传了不支持的值会返回明确报错）")
        private String logEnv;

        @JsonProperty(value = "contextId")
        @JsonPropertyDescription("跟踪ID/traceId，精确追踪一次请求的完整链路，排查时有则必填")
        private String contextId;

        @JsonProperty(value = "keyword")
        @JsonPropertyDescription("日志内容关键词（message 字段短语匹配），例如: 下单失败、订单号")
        private String keyword;

        @JsonProperty(value = "beginTime")
        @JsonPropertyDescription("查询开始时间，格式 yyyy-MM-dd HH:mm:ss（可不带毫秒）。相对表述（最近1小时等）优先传 timeWindow；用户给出具体日期时间才换算后传入；用户完全没提时间时省略（默认当天 00:00:00）。不要凭空猜测当前日期来编造时间")
        private String beginTime;

        @JsonProperty(value = "endTime")
        @JsonPropertyDescription("查询结束时间，格式 yyyy-MM-dd HH:mm:ss（可不带毫秒），与 beginTime 成对传入；传了 timeWindow 时可不传；用户完全没提时间时省略（默认当前时间）")
        private String endTime;

        @JsonProperty(value = "timeWindow")
        @JsonPropertyDescription("相对时间窗，服务端按当前真实时间换算（模型不知道当前时间，相对时间优先传这个）：数字+单位，如 30m(30分钟)、1h(1小时)、12h、1d(1天)，也接受 30分钟/2小时/1天。用户说\"最近N小时/分钟/天\"时传此项，无需再传 beginTime/endTime")
        private String timeWindow;

        @JsonProperty(value = "logLevel")
        @JsonPropertyDescription("日志级别过滤: ERROR/WARN/INFO/DEBUG；排查报错问题优先 ERROR")
        private String logLevel;

        @JsonProperty(value = "pageSize")
        @JsonPropertyDescription("返回条数，1-500，默认20")
        private Integer pageSize;

        @JsonProperty(value = "nextPageContext")
        @JsonPropertyDescription("翻页游标：上一页结果返回的 nextPageContext（listOver=false 时才有），查询下一页时原样传入；仅单应用查询支持")
        private String nextPageContext;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogCountRequest {
        @JsonProperty(value = "appNames")
        @JsonPropertyDescription("应用名列表（如: [\"myapp.java.order.web\"]）或中文别名（如: [\"订单服务\"]）；与正式查询保持一致")
        private List<String> appNames;

        @JsonProperty(value = "logEnv")
        @JsonPropertyDescription("日志环境: product(生产，默认)/qa(测试)。探量与正式查询的 logEnv 必须一致")
        private String logEnv;

        @JsonProperty(value = "contextId")
        @JsonPropertyDescription("跟踪ID/traceId，有则填，探量条件与正式查询保持一致")
        private String contextId;

        @JsonProperty(value = "keyword")
        @JsonPropertyDescription("日志内容关键词，与正式查询保持一致，例如: 下单失败")
        private String keyword;

        @JsonProperty(value = "logLevel")
        @JsonPropertyDescription("日志级别过滤: ERROR/WARN/INFO/DEBUG，与正式查询保持一致")
        private String logLevel;

        @JsonProperty(value = "beginTime")
        @JsonPropertyDescription("查询开始时间，格式 yyyy-MM-dd HH:mm:ss（可不带毫秒）。相对表述优先传 timeWindow；用户完全没提时间时省略（默认当天 00:00:00）。不要编造时间")
        private String beginTime;

        @JsonProperty(value = "endTime")
        @JsonPropertyDescription("查询结束时间，格式 yyyy-MM-dd HH:mm:ss（可不带毫秒），与 beginTime 成对传入；传了 timeWindow 时可不传")
        private String endTime;

        @JsonProperty(value = "timeWindow")
        @JsonPropertyDescription("相对时间窗，服务端按当前真实时间换算：数字+单位，如 30m、1h、12h、1d，也接受 30分钟/2小时/1天")
        private String timeWindow;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogQueryResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("查询是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "returned")
        @JsonPropertyDescription("本页返回条数")
        private Integer returned;

        @JsonProperty(value = "logList")
        @JsonPropertyDescription("日志列表，时间倒序（最新在前）")
        private List<ClsLogEntryVO> logList;

        @JsonProperty(value = "listOver")
        @JsonPropertyDescription("是否已取完（false 时可能未取完，配合 nextPageContext 翻页）")
        private Boolean listOver;

        @JsonProperty(value = "nextPageContext")
        @JsonPropertyDescription("下一页游标（listOver=false 且单应用查询时返回，1小时有效）；作为 nextPageContext 参数再查即可翻页")
        private String nextPageContext;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogCountResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("探量是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "count")
        @JsonPropertyDescription("命中日志总条数（各应用之和；大范围统计可能为采样估算值）")
        private Long count;

        @JsonProperty(value = "topicCounts")
        @JsonPropertyDescription("各应用命中明细（含时间分桶分布 buckets，用于判断问题集中在哪个时段）")
        private List<TopicCount> topicCounts;

        @Data
        @Builder
        @AllArgsConstructor
        @NoArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class TopicCount {
            @JsonProperty(value = "appName")
            @JsonPropertyDescription("应用名")
            private String appName;

            @JsonProperty(value = "count")
            @JsonPropertyDescription("该应用命中条数")
            private Long count;

            @JsonProperty(value = "buckets")
            @JsonPropertyDescription("时间分桶分布（时间正序）：bucket.time=桶起始时间、bucket.count=该桶条数；分布可看出问题集中时段")
            private List<Bucket> buckets;
        }

        @Data
        @Builder
        @AllArgsConstructor
        @NoArgsConstructor
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Bucket {
            @JsonProperty(value = "time")
            @JsonPropertyDescription("桶起始时间")
            private String time;

            @JsonProperty(value = "count")
            @JsonPropertyDescription("该桶条数")
            private Long count;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogContextRequest {
        @JsonProperty(value = "appNames")
        @JsonPropertyDescription("应用名列表（仅支持一个应用，上下文按主题定位），如: [\"myapp.java.order.web\"] 或中文别名如: [\"订单服务\"]")
        private List<String> appNames;

        @JsonProperty(value = "logEnv")
        @JsonPropertyDescription("日志环境: product(生产，默认)/qa(测试)，与锚点日志查询时保持一致")

        private String logEnv;

        @JsonProperty(value = "logId")
        @JsonPropertyDescription("锚点日志 id：queryClsLog 结果里该条日志的 id 字段（PkgId-PkgLogId）；必填")
        private String logId;

        @JsonProperty(value = "logTime")
        @JsonPropertyDescription("锚点日志时间：queryClsLog 结果里该条日志的 logTime 字段（yyyy-MM-dd HH:mm:ss.SSS）；必填")
        private String logTime;

        @JsonProperty(value = "prevCount")
        @JsonPropertyDescription("向前取的条数，默认 10，最大 50")
        private Integer prevCount;

        @JsonProperty(value = "nextCount")
        @JsonPropertyDescription("向后取的条数，默认 10，最大 50")
        private Integer nextCount;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ClsLogContextResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("查询是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "appName")
        @JsonPropertyDescription("应用名（主题名）")
        private String appName;

        @JsonProperty(value = "source")
        @JsonPropertyDescription("实现路径: api=官方上下文接口 / simulate=时间窗口模拟（不支持上下文接口的地域自动降级，结果为近似上下文）")
        private String source;

        @JsonProperty(value = "prevOver")
        @JsonPropertyDescription("前面没有更多日志（已到主题头部）；仅 api 路径返回")
        private Boolean prevOver;

        @JsonProperty(value = "nextOver")
        @JsonPropertyDescription("后面没有更多日志（已到主题尾部）；仅 api 路径返回")
        private Boolean nextOver;

        @JsonProperty(value = "prevLogs")
        @JsonPropertyDescription("锚点之前的日志（时间正序，最靠近锚点的在末尾）")
        private List<ClsLogEntryVO> prevLogs;

        @JsonProperty(value = "nextLogs")
        @JsonPropertyDescription("锚点之后的日志（时间正序）")
        private List<ClsLogEntryVO> nextLogs;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChannelListResult {
        @JsonProperty(value = "channels")
        @JsonPropertyDescription("可用排查渠道列表")
        private List<ChannelInfo> channels;
    }

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChannelInfo {
        @JsonProperty(value = "channelType")
        @JsonPropertyDescription("渠道类型标识，如 tencent-cls-log")
        private String channelType;

        @JsonProperty(value = "channelName")
        @JsonPropertyDescription("渠道名称")
        private String channelName;

        @JsonProperty(value = "channelDesc")
        @JsonPropertyDescription("渠道能力说明")
        private String channelDesc;
    }

}
