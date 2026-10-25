package org.caps.columbo.domain.agent.service.armory.matter.tools;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地知识库工具集（排查经验闭环）：
 * 排查前 searchKnowledge 检索历史案例，需要细节时 readKnowledgeNote 读全文，
 * 排查结论有价值时 appendKnowledgeNote 沉淀速记进 Inbox（自动补 frontmatter，
 * 与库内 AGENTS.md 速记写法对齐）——下次同类问题直接命中，
 * agent 随使用积累变得越来越聪明（ Inbox 由库管理定时任务分拣归档）。
 */
@Slf4j
@Service
public class KnowledgeTools {

    /** 搜索跳过的目录：附件/模板/回收类内容不参与检索 */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "attachments", "templates", ".trash", ".obsidian", "node_modules", ".workbuddy", ".zettelkasten");

    /** 标题命中权重是正文的 5 倍（标题准，正文散） */
    private static final int TITLE_WEIGHT = 5;

    /** 速记 frontmatter 的 system 合法值（与库内 AGENTS.md 一致），缺省归公共平台（AGENTS 归类规矩：拿不准归公共平台） */
    private static final java.util.Set<String> VALID_SYSTEMS = java.util.Set.of("严肃医疗", "消费医疗", "公共平台");

    private static final DateTimeFormatter NAME_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter FRONTMATTER_DATE = LocalDateTimeUtils.DATE_FORMATTER;

    @Resource
    private KnowledgeProperties properties;

    @Autowired(required = false)
    private org.caps.columbo.domain.agent.service.knowledge.VectorKnowledgeService vectorSearchService;

    @Tool(description = "检索本地知识库（个人排查经验库，含历史踩坑速记/疑难攻关/概念页）。排查任何问题前先调用本工具：用问题的关键词（如报错文案、系统名、异常类名、业务术语）搜一遍，命中说明之前处理过类似问题，先读旧案例能少走弯路。可传多个关键词提高召回（多关键词按综合命中排序）。返回命中笔记的路径与片段，细节用 readKnowledgeNote 读取。")
    public KnowledgeSearchResult searchKnowledge(KnowledgeSearchRequest request) {
        try {
            Path root = requireRoot();
            List<String> keywords = normalizeKeywords(request == null ? null : request.getKeywords());
            if (keywords.isEmpty()) {
                return KnowledgeSearchResult.builder()
                        .success(false)
                        .errorMsg("请提供至少一个检索关键词")
                        .build();
            }
            List<KnowledgeHit> hits = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .filter(p -> !inSkipDir(root, p))
                        .forEach(p -> score(p, root, keywords).ifPresent(hits::add));
            }
            // RRF 融合（提炼自 waliapi 混合检索，改为只看排名的真 RRF——关键词命中数与
            // 余弦相似度量级不同，加权会被词频主导）：关键词路 + 向量语义路按排名合并
            List<KnowledgeHit> sortedKeyword = hits.stream()
                    .sorted(Comparator.comparingInt(KnowledgeHit::getScore).reversed())
                    .toList();
            java.util.Map<String, Double> rrf = new java.util.LinkedHashMap<>();
            java.util.Map<String, KnowledgeHit> bestByPath = new java.util.LinkedHashMap<>();
            for (int i = 0; i < sortedKeyword.size(); i++) {
                KnowledgeHit hit = sortedKeyword.get(i);
                rrf.merge(hit.getRelativePath(), 1.0 / (60 + i + 1), Double::sum);
                bestByPath.putIfAbsent(hit.getRelativePath(), hit);
            }
            if (vectorSearchService != null) {
                List<org.caps.columbo.domain.agent.service.knowledge.VectorKnowledgeService.VectorHit> vectorHits =
                        vectorSearchService.search(String.join(" ", keywords), 10);
                for (int i = 0; i < vectorHits.size(); i++) {
                    var vh = vectorHits.get(i);
                    rrf.merge(vh.path(), 1.0 / (60 + i + 1), Double::sum);
                    bestByPath.putIfAbsent(vh.path(), toHit(vh));
                }
            }
            List<KnowledgeHit> merged = rrf.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(e -> {
                        KnowledgeHit hit = bestByPath.get(e.getKey());
                        hit.setScore((int) Math.round(e.getValue() * 10000));
                        return hit;
                    })
                    .toList();
            List<KnowledgeHit> top = merged.stream()
                    .limit(properties.getMaxResults() == null ? 5 : properties.getMaxResults())
                    .toList();
            return KnowledgeSearchResult.builder()
                    .success(true)
                    .totalHits(merged.size())
                    .hits(top.isEmpty() ? null : top)
                    .hint(top.isEmpty() ? "知识库无相关案例，按正常流程排查；排查结论有价值时记得 appendKnowledgeNote 沉淀" : null)
                    .build();
        } catch (AppException e) {
            return KnowledgeSearchResult.builder().success(false).errorMsg(e.getInfo()).build();
        } catch (Exception e) {
            log.error("知识库检索异常", e);
            return KnowledgeSearchResult.builder().success(false).errorMsg("知识库检索异常:" + e.getMessage()).build();
        }
    }

    /** 向量命中 → KnowledgeHit（chunk 原文作摘要与命中行） */
    private static KnowledgeHit toHit(org.caps.columbo.domain.agent.service.knowledge.VectorKnowledgeService.VectorHit vh) {
        String fileName = vh.path().substring(vh.path().lastIndexOf('/') + 1).replace(".md", "");
        String chunk = vh.chunk().trim();
        String summary = chunk.length() > 160 ? chunk.substring(0, 160) + "…" : chunk;
        return KnowledgeHit.builder()
                .title(fileName)
                .relativePath(vh.path())
                .summary(summary)
                .matchedLines(List.of(summary))
                .build();
    }

    @Tool(description = "读取知识库笔记全文（searchKnowledge 返回的 relativePath）。返回 markdown 原文，超长自动截断。")
    public KnowledgeReadResult readKnowledgeNote(KnowledgeReadRequest request) {
        try {
            Path root = requireRoot();
            String relative = request == null || request.getRelativePath() == null ? "" : request.getRelativePath().trim();
            if (relative.isEmpty()) {
                return KnowledgeReadResult.builder().success(false).errorMsg("relativePath 不能为空").build();
            }
            Path target = resolveInside(root, relative);
            if (!Files.isRegularFile(target)) {
                return KnowledgeReadResult.builder().success(false).errorMsg("笔记不存在: " + relative).build();
            }
            int maxBytes = properties.getMaxFileBytes() == null ? 262144 : properties.getMaxFileBytes();
            String content = Files.readString(target, StandardCharsets.UTF_8);
            boolean truncated = content.getBytes(StandardCharsets.UTF_8).length > maxBytes;
            if (truncated) {
                content = truncateBytes(content, maxBytes) + "\n...（超长已截断）";
            }
            return KnowledgeReadResult.builder()
                    .success(true)
                    .relativePath(relative)
                    .content(content)
                    .truncated(truncated)
                    .build();
        } catch (AppException e) {
            return KnowledgeReadResult.builder().success(false).errorMsg(e.getInfo()).build();
        } catch (Exception e) {
            return KnowledgeReadResult.builder().success(false).errorMsg("笔记读取失败:" + e.getMessage()).build();
        }
    }

    @Tool(description = "排查结束后沉淀速记到知识库收件箱（00-Inbox），下次同类问题可被 searchKnowledge 命中，入库后由库管理定时任务分拣归档。只在结论已验证有效、有复用价值时写（普通问题不用写）。title 用一句话概括问题（如:下单失败-库存扣减为0未拦截），system 填业务系统（严肃医疗/消费医疗/公共平台，拿不准填公共平台），content 按『现象/环境/排查过程/根因/解决方案/关联』结构组织 markdown。红线：不写入密码/密钥/Token/连接串，手机号、身份证、真实 IP 等先脱敏（打码/占位）再写。")
    public KnowledgeAppendResult appendKnowledgeNote(KnowledgeAppendRequest request) {
        try {
            Path root = requireRoot();
            if (Boolean.FALSE.equals(properties.getWriteEnabled())) {
                return KnowledgeAppendResult.builder()
                        .success(false)
                        .errorMsg("知识库写入未开启（troubleshoot.knowledge.write-enabled=false）")
                        .build();
            }
            String title = request == null || request.getTitle() == null ? "" : request.getTitle().trim();
            String content = request == null ? "" : request.getContent();
            if (title.isEmpty() || content == null || content.isBlank()) {
                return KnowledgeAppendResult.builder().success(false).errorMsg("title 与 content 不能为空").build();
            }
            Path inbox = root.resolve(properties.getInboxDir() == null ? "00-Inbox" : properties.getInboxDir()).normalize();
            if (!inbox.startsWith(root)) {
                return KnowledgeAppendResult.builder().success(false).errorMsg("inbox 目录配置非法").build();
            }
            Files.createDirectories(inbox);
            Path target = inbox.resolve("速记-" + LocalDate.now().format(NAME_DATE) + "-" + sanitize(title) + ".md");
            int seq = 1;
            while (Files.exists(target)) {
                target = inbox.resolve("速记-" + LocalDate.now().format(NAME_DATE) + "-" + sanitize(title) + "-" + seq++ + ".md");
            }
            Files.writeString(target, withFrontmatter(request.getSystem(), content), StandardCharsets.UTF_8);
            log.info("知识库速记已写入 {}", target);
            return KnowledgeAppendResult.builder()
                    .success(true)
                    .writtenPath(root.relativize(target).toString())
                    .build();
        } catch (AppException e) {
            return KnowledgeAppendResult.builder().success(false).errorMsg(e.getInfo()).build();
        } catch (Exception e) {
            log.error("知识库写入异常", e);
            return KnowledgeAppendResult.builder().success(false).errorMsg("知识库写入异常:" + e.getMessage()).build();
        }
    }

    /**
     * 自动补速记 frontmatter（对齐库内 AGENTS.md 速记写法）：tags/date/system。
     * 正文已带 frontmatter（以 --- 开头）时原样保留，避免双层头。
     */
    private static String withFrontmatter(String system, String content) {
        String body = content == null ? "" : content;
        if (body.stripLeading().startsWith("---")) {
            return body;
        }
        String normalizedSystem = system == null ? "" : system.trim();
        if (!VALID_SYSTEMS.contains(normalizedSystem)) {
            normalizedSystem = "公共平台";
        }
        return "---\ntags: [排查速记]\ndate: \"" + LocalDate.now().format(FRONTMATTER_DATE)
                + "\"\nsystem: " + normalizedSystem + "\n---\n\n" + body;
    }

    // ==================== 内部实现 ====================

    private Path requireRoot() {
        String rootPath = properties.getRootPath();
        if (rootPath == null || rootPath.isBlank() || !Files.isDirectory(Path.of(rootPath))) {
            throw new AppException(ResponseCode.B002.getCode(),
                    "本地知识库未配置或目录不存在：请在 troubleshoot.knowledge.root-path 配置笔记库根目录");
        }
        return Path.of(rootPath).toAbsolutePath().normalize();
    }

    /** 解析相对路径并强制限制在根目录内（防路径逃逸） */
    private Path resolveInside(Path root, String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "路径越界: " + relative);
        }
        return target;
    }

    private static boolean inSkipDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizeKeywords(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> k.trim().toLowerCase())
                .distinct()
                .toList();
    }

    /** 单篇打分：标题命中×5 + 正文命中×1，零命中返回 empty */
    private java.util.Optional<KnowledgeHit> score(Path file, Path root, List<String> keywords) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String lower = content.toLowerCase();
            String title = file.getFileName().toString().replaceFirst("\\.md$", "");
            String lowerTitle = title.toLowerCase();
            int score = 0;
            List<String> matchedLines = new ArrayList<>();
            for (String keyword : keywords) {
                if (lowerTitle.contains(keyword)) {
                    score += TITLE_WEIGHT;
                }
                int body = countOccurrences(lower, keyword);
                if (body > 0) {
                    score += body;
                    extractMatchedLines(content, keyword, matchedLines);
                }
            }
            if (score == 0) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(KnowledgeHit.builder()
                    .title(title)
                    .relativePath(root.relativize(file).toString())
                    .score(score)
                    .summary(firstMeaningfulLine(content))
                    .matchedLines(matchedLines.isEmpty() ? null : matchedLines.stream().limit(3).toList())
                    .build());
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void extractMatchedLines(String content, String keyword, List<String> matchedLines) {
        if (matchedLines.size() >= 3) {
            return;
        }
        for (String line : content.split("\n")) {
            if (matchedLines.size() >= 3) {
                return;
            }
            if (line.toLowerCase().contains(keyword) && !line.isBlank()) {
                matchedLines.add(truncateLine(line));
            }
        }
    }

    /** 首个有意义的行（跳过 frontmatter/空行），作为笔记摘要 */
    private static String firstMeaningfulLine(String content) {
        boolean inFrontMatter = false;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("---")) {
                inFrontMatter = !inFrontMatter && content.startsWith("---");
                continue;
            }
            if (inFrontMatter || trimmed.isEmpty()) {
                continue;
            }
            return truncateLine(trimmed.replaceFirst("^#+\\s*", ""));
        }
        return "";
    }

    private static String truncateLine(String line) {
        return line.length() <= 160 ? line : line.substring(0, 160) + "...";
    }

    private static String truncateBytes(String content, int maxBytes) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }

    /** 文件名清洗：去路径分隔符与非法字符，限长 60 */
    private static String sanitize(String title) {
        String cleaned = title.replaceAll("[/\\\\:*?\"<>|#\\[\\]]", "-").trim();
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned.isEmpty() ? LocalDateTime.now().format(NAME_DATE) : cleaned;
    }

    // ==================== 工具参数/返回 DTO ====================

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeSearchRequest {
        @JsonProperty(value = "keywords")
        @JsonPropertyDescription("检索关键词列表（2-4 个为宜），如: [\"下单失败\", \"库存扣减\"]、[\"Connection reset\", \"GlobalExceptionHandler\"]。用问题现象/报错文案/系统名/业务术语")
        private List<String> keywords;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeSearchResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("检索是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "totalHits")
        @JsonPropertyDescription("命中笔记总数（含未返回的）")
        private Integer totalHits;

        @JsonProperty(value = "hits")
        @JsonPropertyDescription("命中的笔记列表（按相关度倒序，最多5条）")
        private List<KnowledgeHit> hits;

        @JsonProperty(value = "hint")
        @JsonPropertyDescription("无命中时的提示")
        private String hint;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeHit {
        @JsonProperty(value = "title")
        @JsonPropertyDescription("笔记标题（文件名去扩展名）")
        private String title;

        @JsonProperty(value = "relativePath")
        @JsonPropertyDescription("相对路径（readKnowledgeNote 的入参）")
        private String relativePath;

        @JsonProperty(value = "score")
        @JsonPropertyDescription("相关度得分（标题命中权重高）")
        private Integer score;

        @JsonProperty(value = "summary")
        @JsonPropertyDescription("笔记首行摘要")
        private String summary;

        @JsonProperty(value = "matchedLines")
        @JsonPropertyDescription("关键词命中的正文片段（最多3行）")
        private List<String> matchedLines;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeReadRequest {
        @JsonProperty(value = "relativePath")
        @JsonPropertyDescription("searchKnowledge 返回的 relativePath")
        private String relativePath;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeReadResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("读取是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "relativePath")
        @JsonPropertyDescription("笔记相对路径")
        private String relativePath;

        @JsonProperty(value = "content")
        @JsonPropertyDescription("markdown 原文（超长截断）")
        private String content;

        @JsonProperty(value = "truncated")
        @JsonPropertyDescription("是否因超长截断")
        private Boolean truncated;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeAppendRequest {
        @JsonProperty(value = "title")
        @JsonPropertyDescription("一句话概括问题，如: 下单失败-库存扣减为0未拦截")
        private String title;

        @JsonProperty(value = "system")
        @JsonPropertyDescription("业务系统：严肃医疗 / 消费医疗 / 公共平台（拿不准填公共平台）")
        private String system;

        @JsonProperty(value = "content")
        @JsonPropertyDescription("速记正文（markdown，无需 frontmatter，系统自动补）：## 现象 / ## 环境（区域+系统） / ## 排查过程 / ## 根因 / ## 解决方案 / ## 关联（本次查到的日志/代码位置）。红线：不写密钥/Token，手机号等脱敏后再写")
        private String content;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class KnowledgeAppendResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("写入是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "writtenPath")
        @JsonPropertyDescription("写入的笔记相对路径（已落 Inbox，用户月底提炼）")
        private String writtenPath;
    }
}
