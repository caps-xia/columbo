package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.ResolvedTopic;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CLS 主题注册表（跨站点）：把应用名/中文名/别名解析为「站点+主题」配置。
 * 选择语义：精确匹配优先，模糊匹配唯一命中才采用。
 * 视图 = yml 静态注册（中文名/别名等业务语义在此维护）+ 线上同步 overlay
 * （DescribeTopics 拉取的 TopicName->TopicId，新应用自动补录、占位 topicId 自动修正）。
 */
@Component
public class ClsTopicRegistry {

    private final ClsProperties clsProperties;

    /** 线上同步 overlay：siteKey -> (TopicName -> TopicId)；整体替换保证读侧一致 */
    private final Map<String, Map<String, String>> syncedTopics = new ConcurrentHashMap<>();

    public ClsTopicRegistry(ClsProperties clsProperties) {
        this.clsProperties = clsProperties;
    }

    /**
     * 合并线上主题（DescribeTopics 产物）：线上 TopicId 永远生效（视为真相），
     * 中文名/别名沿用 yml 已有配置；yml 未登记的主题自动补进注册表。
     *
     * @return [added, updated]：新增主题数 / topicId 被修正的主题数（含占位符替换）
     */
    public long[] applySync(String siteKey, Map<String, String> remoteTopics) {
        Map<String, ClsProperties.Topic> current = effectiveTopicsOf(siteKey);
        long added = 0;
        long updated = 0;
        for (Map.Entry<String, String> entry : remoteTopics.entrySet()) {
            ClsProperties.Topic existing = current.get(entry.getKey());
            if (existing == null) {
                added++;
            } else if (!entry.getValue().equals(existing.getTopicId())) {
                updated++;
            }
        }
        syncedTopics.put(siteKey, Map.copyOf(remoteTopics));
        return new long[]{added, updated};
    }

    /** 解析查询目标：跨全部站点按 注册key/中文名/别名 精确->模糊 匹配；模糊命中多个抛异常由 agent 转述候选 */
    public List<ResolvedTopic> resolve(List<String> appNames) {
        Map<String, ClsProperties.Site> sites = effectiveSites();
        if (sites.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "腾讯云CLS未注册任何站点，请在 troubleshoot.cls.sites 中维护站点配置（region/密钥/主题表）");
        }
        if (appNames == null || appNames.stream().allMatch(n -> n == null || n.isBlank())) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "请提供应用名（如 hnhy.java.hospital.admin.api.web）或中文别名至少一项；已接入：" + knownTopicsSummary());
        }
        LinkedHashMap<String, ResolvedTopic> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String name : appNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            ResolvedTopic topic = matchByName(name.trim());
            if (topic == null) {
                unknown.add(name.trim());
            } else {
                resolved.put(topic.getSite() + ":" + topic.getKey(), topic);
            }
        }
        if (!unknown.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "无法识别应用 " + unknown + "，已接入：" + knownTopicsSummary());
        }
        if (resolved.isEmpty()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "请提供应用名或中文别名至少一项；已接入：" + knownTopicsSummary());
        }
        for (ResolvedTopic topic : resolved.values()) {
            requireConfiguredTopicId(topic);
        }
        return new ArrayList<>(resolved.values());
    }

    /** 供路由按完整应用名直查（跨站点，可能未注册，返回 null 由调用方处理） */
    public ResolvedTopic findByKey(String key) {
        for (Map.Entry<String, ClsProperties.Site> siteEntry : effectiveSites().entrySet()) {
            ClsProperties.Topic topic = siteEntry.getValue().getTopics().get(key);
            if (topic != null) {
                return toResolved(siteEntry.getKey(), key, topic);
            }
        }
        return null;
    }

    /** channelDesc 用：按站点分组的应用名摘要 */
    public String knownTopicsSummary() {
        Map<String, ClsProperties.Site> sites = effectiveSites();
        if (sites.isEmpty()) {
            return "（暂未注册站点，请在 troubleshoot.cls.sites 中维护站点与主题注册表）";
        }
        return sites.entrySet().stream()
                .map(siteEntry -> {
                    String label = clsProperties.siteLabel(siteEntry.getKey());
                    String apps = siteEntry.getValue().getTopics().keySet().stream()
                            .collect(Collectors.joining("、"));
                    return label + "[" + (apps.isEmpty() ? "暂无主题" : apps) + "]";
                })
                .collect(Collectors.joining("；"));
    }

    private ResolvedTopic matchByName(String name) {
        for (Map.Entry<String, ClsProperties.Site> siteEntry : effectiveSites().entrySet()) {
            for (Map.Entry<String, ClsProperties.Topic> entry : siteEntry.getValue().getTopics().entrySet()) {
                if (equalsLoosely(entry.getKey(), name)
                        || equalsLoosely(entry.getValue().getName(), name)
                        || aliasesOf(entry.getValue()).stream().anyMatch(alias -> equalsLoosely(alias, name))) {
                    return toResolved(siteEntry.getKey(), entry.getKey(), entry.getValue());
                }
            }
        }
        List<ResolvedTopic> candidates = new ArrayList<>();
        for (Map.Entry<String, ClsProperties.Site> siteEntry : effectiveSites().entrySet()) {
            for (Map.Entry<String, ClsProperties.Topic> entry : siteEntry.getValue().getTopics().entrySet()) {
                ClsProperties.Topic topic = entry.getValue();
                if (containsLoosely(entry.getKey(), name)
                        || containsLoosely(topic.getName(), name)
                        || aliasesOf(topic).stream().anyMatch(alias -> containsLoosely(alias, name))) {
                    candidates.add(toResolved(siteEntry.getKey(), entry.getKey(), topic));
                }
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.size() > 1) {
            String candidateDesc = candidates.stream()
                    .map(t -> clsProperties.siteLabel(t.getSite()) + ":" + t.getKey())
                    .collect(Collectors.joining("、"));
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "应用名 \"" + name + "\" 匹配到多个主题：" + candidateDesc + "，请明确具体要查哪一个");
        }
        return null;
    }

    /** 主题ID未配置（占位符）时给明确的配置指引，而不是带着占位符调 API 报晦涩错误 */
    private void requireConfiguredTopicId(ResolvedTopic topic) {
        if (!StringUtils.hasText(topic.getTopicId())
                || topic.getTopicId().startsWith(ClsProperties.PLACEHOLDER_PREFIX)) {
            throw new AppException(ResponseCode.B002.getCode(),
                    "站点[" + clsProperties.siteLabel(topic.getSite()) + "]应用 " + topic.getKey()
                            + " 的日志主题ID未配置，请调用 POST /api/v1/cls_topics_sync 自动同步"
                            + "（或检查 troubleshoot.cls.sites." + topic.getSite() + ".topics 配置与站点密钥）");
        }
    }

    /** ============ yml + 线上同步的合并视图 ============ */

    /** 合并视图站点表：结构与 yml 相同，主题表 = yml 主题（中文名/别名）+ 线上 TopicId 覆盖/补录 */
    private Map<String, ClsProperties.Site> effectiveSites() {
        Map<String, ClsProperties.Site> ymlSites = clsProperties.getSites();
        Map<String, ClsProperties.Site> merged = new LinkedHashMap<>();
        for (Map.Entry<String, ClsProperties.Site> entry : ymlSites.entrySet()) {
            ClsProperties.Site site = entry.getValue();
            ClsProperties.Site copy = new ClsProperties.Site();
            copy.setLabel(site.getLabel());
            copy.setRegion(site.getRegion());
            copy.setLogsetId(site.getLogsetId());
            copy.setSecretId(site.getSecretId());
            copy.setSecretKey(site.getSecretKey());
            copy.setEndpoint(site.getEndpoint());
            copy.setEnv(site.getEnv());
            copy.setEnvs(site.getEnvs());
            copy.setEnvTagKey(site.getEnvTagKey());
            copy.setTopics(effectiveTopicsOf(entry.getKey()));
            merged.put(entry.getKey(), copy);
        }
        return merged;
    }

    /** 站点主题合并视图：线上同步的 TopicId 覆盖 yml（线上为真相），yml 未登记的主题自动补录 */
    private Map<String, ClsProperties.Topic> effectiveTopicsOf(String siteKey) {
        ClsProperties.Site site = clsProperties.siteOf(siteKey);
        Map<String, ClsProperties.Topic> merged = new LinkedHashMap<>();
        if (site != null && site.getTopics() != null) {
            merged.putAll(site.getTopics());
        }
        Map<String, String> synced = syncedTopics.get(siteKey);
        if (synced != null) {
            for (Map.Entry<String, String> entry : synced.entrySet()) {
                ClsProperties.Topic topic = merged.get(entry.getKey());
                if (topic == null) {
                    topic = new ClsProperties.Topic();
                    topic.setTopicId(entry.getValue());
                    merged.put(entry.getKey(), topic);
                } else if (!entry.getValue().equals(topic.getTopicId())) {
                    // 线上 TopicId 与 yml 不一致（含占位符）：拷贝后替换，不动 yml 绑定的配置对象
                    ClsProperties.Topic copy = new ClsProperties.Topic();
                    copy.setName(topic.getName());
                    copy.setAliases(topic.getAliases());
                    copy.setTopicId(entry.getValue());
                    merged.put(entry.getKey(), copy);
                }
            }
        }
        return merged;
    }

    private static ResolvedTopic toResolved(String siteKey, String key, ClsProperties.Topic topic) {
        return ResolvedTopic.builder()
                .site(siteKey)
                .key(key)
                .name(topic.getName())
                .topicId(topic.getTopicId())
                .build();
    }

    private static List<String> aliasesOf(ClsProperties.Topic topic) {
        return topic.getAliases() == null ? List.of() : topic.getAliases();
    }

    private static boolean equalsLoosely(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean containsLoosely(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        String l = left.trim().toLowerCase();
        String r = right.trim().toLowerCase();
        return l.contains(r) || r.contains(l);
    }
}
