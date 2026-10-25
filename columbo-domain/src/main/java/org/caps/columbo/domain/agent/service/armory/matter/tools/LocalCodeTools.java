package org.caps.columbo.domain.agent.service.armory.matter.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.caps.columbo.domain.troubleshoot.model.properties.CodeAnalysisProperties;
import org.caps.columbo.domain.troubleshoot.model.properties.ClsProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

/**
 * 本地代码分析工具集，通过 @Tool 暴露给 LLM。
 * 只读访问 troubleshoot.code-analysis.root-path 下的工程源码，配合日志工具做根因分析。
 */
@Slf4j
@Service
public class LocalCodeTools {

    /** 遍历时跳过的目录：构建产物、依赖、IDE/AI 工具元数据等 */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            ".git", ".svn", ".idea", ".vscode", ".settings", ".mvn",
            "target", "node_modules", "dist", "build", "out", "logs", "log", "upload",
            ".claude", ".agents", ".agent", ".codex", ".comet", ".zcode", ".codegraph", "openspec");

    /** 单行内容截断长度，避免超长行撑爆上下文 */
    private static final int MAX_LINE_CHARS = 240;

    /** 单次遍历的文件数上限，防止超大目录拖垮请求 */
    private static final int MAX_VISIT_FILES = 20000;

    @Resource
    private CodeAnalysisProperties properties;

    /** 用于校验路由结果对应的腾讯云CLS主题是否已配置（可选依赖，单测可不注入） */
    @Resource
    private ClsProperties clsProperties;

    @Tool(description = "列出本地可分析的代码工程清单及各自说明。支持按环境维护多套代码副本（routing.environments 配置 code-path/branch），工程目录名完全相同，本清单对各套副本都适用。排查开始时先调用它确认工程目录名（searchCode/readCodeFile/findFiles 的 projectName 参数要用这里的 dirName）。")
    public ProjectListResult listLocalProjects() {
        try {
            Path root = requireRoot();
            List<ProjectInfo> projectInfos = new ArrayList<>();
            List<String> dirNames = new ArrayList<>();
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory)
                        .forEach(dir -> dirNames.add(dir.getFileName().toString()));
            }
            java.util.Collections.sort(dirNames);
            for (String dirName : dirNames) {
                CodeAnalysisProperties.Project registered = properties.getProjects().get(dirName);
                projectInfos.add(ProjectInfo.builder()
                        .dirName(dirName)
                        .description(registered == null ? null : registered.getDescription())
                        .build());
            }
            return ProjectListResult.builder()
                    .success(true)
                    .rootPath(root.toString())
                    .projects(projectInfos)
                    .build();
        } catch (Exception e) {
            return ProjectListResult.builder().success(false).errorMsg(e.getMessage()).build();
        }
    }

    @Tool(description = "在本地工程源码中按关键词/正则搜索，返回命中行（文件相对路径+行号+内容）。用于：异常栈类名定位源码、报错文案/错误码反查代码、方法名/变量名检索。传 environment（环境名，以 resolveRouting 返回为准）选择对应环境的代码副本——各环境分支不同，传错环境会定位到错误代码。类名用简名即可（如 OrderController）。尽量传 projectName 限定工程（目录名见 listLocalProjects），不传则搜索整个代码根目录、较慢。可传 fileExt 过滤文件类型（如 java/xml/yml/properties/js/sql）。")
    public CodeSearchResult searchCode(CodeSearchRequest request) {
        try {
            if (request == null || isBlank(request.getKeyword())) {
                return CodeSearchResult.builder().success(false)
                        .errorMsg("请提供 keyword（关键词或正则表达式）").build();
            }
            EnvRoot envRoot = resolveRoot(request.getEnvironment());
            Path searchRoot = resolveSearchRoot(envRoot.root, request.getProjectName());
            Pattern pattern = compilePattern(request.getKeyword(), Boolean.TRUE.equals(request.getUseRegex()));
            String ext = normalizeExt(request.getFileExt());
            int maxMatches = clampMatches(request.getMaxResults());

            List<CodeMatch> matches = new ArrayList<>();
            int[] scanned = {0};
            walkFiles(searchRoot, file -> {
                scanned[0]++;
                if (ext != null && !file.getFileName().toString().toLowerCase().endsWith(ext)) {
                    return;
                }
                if (matches.size() >= maxMatches || fileSizeOver(file)) {
                    return;
                }
                String content = readText(file);
                if (content == null) {
                    return;
                }
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (pattern.matcher(lines[i]).find()) {
                        if (matches.size() >= maxMatches) {
                            return;
                        }
                        matches.add(CodeMatch.builder()
                                .file(relativePath(file, envRoot.root))
                                .lineNo(i + 1)
                                .lineText(truncateLine(lines[i]))
                                .build());
                    }
                }
            });
            return CodeSearchResult.builder()
                    .success(true)
                    .environment(envRoot.environmentName)
                    .branch(envRoot.branch)
                    .searchRoot(relativePath(searchRoot, envRoot.root))
                    .scannedFiles(scanned[0])
                    .returned(matches.size())
                    .truncated(matches.size() >= maxMatches)
                    .matches(matches)
                    .notes(envRoot.note == null ? null : List.of(envRoot.note))
                    .build();
        } catch (PatternSyntaxException e) {
            return CodeSearchResult.builder().success(false)
                    .errorMsg("正则表达式非法: " + e.getMessage()).build();
        } catch (Exception e) {
            log.warn("本地代码搜索异常", e);
            return CodeSearchResult.builder().success(false).errorMsg("本地代码搜索异常: " + e.getMessage()).build();
        }
    }

    @Tool(description = "按行号范围读取本地工程源码文件内容（带行号返回）。用于：阅读异常行号附近的代码、追调用链（Controller→Service→DAO）、查看配置/SQL文件。filePath 用工具返回的相对路径（如 myapp-service/src/main/java/com/demo/Foo.java）。environment（环境名，以 resolveRouting 返回为准）要与 searchCode 时一致，否则可能读到错误分支的代码。不传行号默认从头读，单次最多返回 maxReadLines（默认150）行，读长文件时分段调用。")
    public CodeFileReadResult readCodeFile(CodeFileReadRequest request) {
        try {
            if (request == null || isBlank(request.getFilePath())) {
                return CodeFileReadResult.builder().success(false)
                        .errorMsg("请提供 filePath（相对代码根目录的路径）").build();
            }
            EnvRoot envRoot = resolveRoot(request.getEnvironment());
            List<String> notes = envRoot.note == null ? new ArrayList<>() : new ArrayList<>(List.of(envRoot.note));
            Path file = locateFile(envRoot, request.getFilePath(), notes);
            if (file == null) {
                return CodeFileReadResult.builder().success(false)
                        .errorMsg("文件不存在: " + request.getFilePath() + "（请核对 environment 与路径）").build();
            }
            if (fileSizeOver(file)) {
                return CodeFileReadResult.builder().success(false)
                        .errorMsg("文件超过大小上限 " + properties.getMaxFileBytes() + " 字节，请用 searchCode 定位后按需读取").build();
            }
            String content = readText(file);
            if (content == null) {
                return CodeFileReadResult.builder().success(false)
                        .errorMsg("文件为二进制或不可读: " + request.getFilePath()).build();
            }
            String[] lines = content.split("\n", -1);
            int totalLines = lines.length;
            int startLine = clampLine(request.getStartLine(), 1, totalLines);
            int maxRead = properties.getMaxReadLines() == null ? 150 : properties.getMaxReadLines();
            int endLine = request.getEndLine() == null ? totalLines : request.getEndLine();
            endLine = Math.max(startLine, Math.min(endLine, startLine + maxRead - 1));
            endLine = Math.min(endLine, totalLines);
            StringBuilder text = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                text.append(i).append('\t').append(lines[i - 1].stripTrailing()).append('\n');
            }
            return CodeFileReadResult.builder()
                    .success(true)
                    .environment(envRoot.environmentName)
                    .branch(envRoot.branch)
                    .filePath(relativePath(file, envRoot.root))
                    .startLine(startLine)
                    .endLine(endLine)
                    .totalLines(totalLines)
                    .truncated(endLine < totalLines)
                    .content(text.toString())
                    .notes(notes.isEmpty() ? null : notes)
                    .build();
        } catch (Exception e) {
            log.warn("本地代码读取异常", e);
            return CodeFileReadResult.builder().success(false).errorMsg("本地代码读取异常: " + e.getMessage()).build();
        }
    }

    @Tool(description = "按文件名（支持正则，如 .*OrderController.* ）在本地工程中定位文件，返回相对路径。用于：异常栈只有类名、不知道文件在哪时先把文件找出来；或查找配置文件/SQL脚本。environment（环境名，以 resolveRouting 返回为准）选择对应环境的代码副本。尽量传 projectName 限定工程。")
    public FileFindResult findFiles(FileFindRequest request) {
        try {
            if (request == null || isBlank(request.getFileNamePattern())) {
                return FileFindResult.builder().success(false)
                        .errorMsg("请提供 fileNamePattern（文件名正则，如 .*Coupon.*Controller.* ）").build();
            }
            EnvRoot envRoot = resolveRoot(request.getEnvironment());
            Path searchRoot = resolveSearchRoot(envRoot.root, request.getProjectName());
            Pattern pattern = compilePattern(request.getFileNamePattern(), true);
            int maxMatches = clampMatches(request.getMaxResults());

            List<FileHit> hits = new ArrayList<>();
            walkFiles(searchRoot, file -> {
                if (hits.size() >= maxMatches) {
                    return;
                }
                if (pattern.matcher(file.getFileName().toString()).find()) {
                    hits.add(FileHit.builder()
                            .file(relativePath(file, envRoot.root))
                            .sizeBytes(fileSizeBytes(file))
                            .build());
                }
            });
            return FileFindResult.builder()
                    .success(true)
                    .environment(envRoot.environmentName)
                    .branch(envRoot.branch)
                    .searchRoot(relativePath(searchRoot, envRoot.root))
                    .returned(hits.size())
                    .truncated(hits.size() >= maxMatches)
                    .files(hits)
                    .notes(envRoot.note == null ? null : List.of(envRoot.note))
                    .build();
        } catch (PatternSyntaxException e) {
            return FileFindResult.builder().success(false)
                    .errorMsg("正则表达式非法: " + e.getMessage()).build();
        } catch (Exception e) {
            log.warn("本地文件定位异常", e);
            return FileFindResult.builder().success(false).errorMsg("本地文件定位异常: " + e.getMessage()).build();
        }
    }

    @Tool(description = "话术路由：把『环境+业务线+现象』的口语描述（如：生产环境 订单 下单失败）解析为具体应用与本地代码位置，并标注每个候选的 CLS 应用名（clsAppName，已配置主题时可直接查日志）。用户一句话说不清哪个系统时，先调用本工具确定查哪些应用、搜哪些本地工程，再去查日志/搜代码。环境或业务线识别不出时返回全部候选并带提示，由你向用户确认或按现象选择。")
    public RoutingResult resolveRouting(RoutingRequest request) {
        try {
            String environment = request == null ? null : blankToNull(request.getEnvironment());
            String businessLine = request == null ? null : blankToNull(request.getBusinessLine());
            String symptom = request == null ? null : blankToNull(request.getSymptom());
            CodeAnalysisProperties.Routing routing = properties.getRouting() == null
                    ? new CodeAnalysisProperties.Routing() : properties.getRouting();
            List<String> notes = new ArrayList<>();

            List<CodeAnalysisProperties.Environment> environments = routing.getEnvironments();
            List<CodeAnalysisProperties.Environment> matchedEnvs =
                    matchByKeywords(environment, environments, CodeAnalysisProperties.Environment::getKeywords);
            if (matchedEnvs.isEmpty()) {
                if (environment != null) {
                    notes.add("未识别环境[" + environment + "]，已列出全部环境，请与用户确认");
                }
                matchedEnvs = environments;
            }

            List<CodeAnalysisProperties.BusinessLine> lines = routing.getBusinessLines();
            List<CodeAnalysisProperties.BusinessLine> matchedLines =
                    matchByKeywords(businessLine, lines, CodeAnalysisProperties.BusinessLine::getKeywords);
            if (matchedLines.isEmpty() && symptom != null) {
                // 业务线没说但现象里带了关键词（如"HPV打不开"），用现象兜底匹配
                matchedLines = matchByKeywords(symptom, lines, CodeAnalysisProperties.BusinessLine::getKeywords);
            }
            if (matchedLines.isEmpty()) {
                if (businessLine != null) {
                    notes.add("未识别业务线[" + businessLine + "]，已列出全部业务线，请结合现象确认");
                } else {
                    notes.add("未提供业务线，已列出全部业务线候选");
                }
                matchedLines = lines;
            }

            List<RoutingCandidate> candidates = new ArrayList<>();
            List<EnvInfo> envInfos = new ArrayList<>();
            for (CodeAnalysisProperties.Environment env : matchedEnvs) {
                envInfos.add(EnvInfo.builder()
                        .name(env.getName())
                        .matched(environment != null)
                        .codeBranch(env.getCodeBranch())
                        .note(env.getNote())
                        .build());
                if (env.getNote() != null && !env.getNote().isBlank()) {
                    notes.add(env.getName() + "：" + env.getNote());
                }
                for (CodeAnalysisProperties.BusinessLine line : matchedLines) {
                    for (CodeAnalysisProperties.Station station : line.getStations()) {
                        // CLS 主题：环境前缀 + 站点后缀拼出应用名（TopicName）
                        String clsKey = env.getClsAppPrefix() == null || env.getClsAppPrefix().isBlank()
                                ? null : env.getClsAppPrefix() + "." + station.getAppSuffix();
                        boolean clsRegistered = clsTopicRegistered(clsKey);
                        candidates.add(RoutingCandidate.builder()
                                .environment(env.getName())
                                .businessLine(line.getName())
                                .station(station.getName())
                                .role(station.getRole())
                                .clsAppName(clsRegistered ? clsKey : null)
                                .clsRegistered(clsRegistered)
                                .localProject(station.getLocalProject())
                                .codeBranch(env.getCodeBranch())
                                .searchHints(station.getSearchHints())
                                .build());
                    }
                }
            }
            return RoutingResult.builder()
                    .success(true)
                    .environments(envInfos)
                    .candidates(candidates)
                    .notes(notes.isEmpty() ? null : notes)
                    .build();
        } catch (Exception e) {
            log.warn("话术路由解析异常", e);
            return RoutingResult.builder().success(false).errorMsg("话术路由解析异常: " + e.getMessage()).build();
        }
    }

    // ==================== 内部实现 ====================

    /** 环境对应的代码目录：root=实际读取的根目录，environmentName/branch 用于结果回显，note 记录降级原因 */
    private static class EnvRoot {
        final Path root;
        final String environmentName;
        final String branch;
        final String note;

        EnvRoot(Path root, String environmentName, String branch, String note) {
            this.root = root;
            this.environmentName = environmentName;
            this.branch = branch;
            this.note = note;
        }
    }

    /**
     * 按环境解析代码根目录：环境口语（如"生产"）命中路由词典且配置了 code-path 则用环境副本目录，
     * 否则回退默认 root-path 并在 note 里说明（各环境副本对应不同 git 分支，在 routing.environments.code-branch 配置）。
     */
    private EnvRoot resolveRoot(String environment) {
        Path defaultRoot = requireRoot();
        String envText = blankToNull(environment);
        if (envText == null) {
            return new EnvRoot(defaultRoot, null, null, null);
        }
        List<CodeAnalysisProperties.Environment> environments = routingEnvironments();
        List<CodeAnalysisProperties.Environment> matched =
                matchByKeywords(envText, environments, CodeAnalysisProperties.Environment::getKeywords);
        if (matched.isEmpty()) {
            return new EnvRoot(defaultRoot, null, null,
                    "未识别环境[" + envText + "]，已读取默认目录（root-path），可用环境见 resolveRouting 返回");
        }
        CodeAnalysisProperties.Environment env = matched.get(0);
        if (isBlank(env.getCodePath())) {
            return new EnvRoot(defaultRoot, env.getName(), env.getCodeBranch(),
                    "环境[" + env.getName() + "]未配置代码目录，已读取默认目录（root-path）");
        }
        Path envRoot = Path.of(env.getCodePath()).toAbsolutePath().normalize();
        if (!Files.isDirectory(envRoot)) {
            return new EnvRoot(defaultRoot, env.getName(), env.getCodeBranch(),
                    "环境[" + env.getName() + "]代码目录不存在: " + envRoot + "，已读取默认目录（root-path）");
        }
        return new EnvRoot(envRoot, env.getName(), env.getCodeBranch(), null);
    }

    /** readCodeFile 的文件定位：先按 environment 对应目录找，找不到再试默认目录与其他环境副本（并记录实际位置） */
    private Path locateFile(EnvRoot envRoot, String filePath, List<String> notes) {
        Path direct = resolveSafely(envRoot.root, filePath);
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        List<CodeAnalysisProperties.Environment> environments = routingEnvironments();
        for (CodeAnalysisProperties.Environment env : environments) {
            if (isBlank(env.getCodePath())) {
                continue;
            }
            Path envPath = Path.of(env.getCodePath()).toAbsolutePath().normalize();
            if (envPath.equals(envRoot.root) || !Files.isDirectory(envPath)) {
                continue;
            }
            Path candidate = resolveSafely(envPath, filePath);
            if (Files.isRegularFile(candidate)) {
                notes.add("文件未在[" + (envRoot.environmentName == null ? "默认" : envRoot.environmentName)
                        + "]目录找到，实际读取的是[" + env.getName() + "]代码副本（分支 " + env.getCodeBranch() + "），请核对环境是否正确");
                return candidate;
            }
        }
        return null;
    }

    private List<CodeAnalysisProperties.Environment> routingEnvironments() {
        if (properties.getRouting() == null || properties.getRouting().getEnvironments() == null) {
            return List.of();
        }
        return properties.getRouting().getEnvironments();
    }

    private Path requireRoot() {
        String rootPath = properties.getRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("未配置 troubleshoot.code-analysis.root-path（本地代码根目录）");
        }
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("本地代码根目录不存在: " + root);
        }
        return root;
    }

    /** projectName 为空返回根目录，否则校验并返回对应工程目录 */
    private Path resolveSearchRoot(Path root, String projectName) {
        if (isBlank(projectName)) {
            return root;
        }
        Path projectDir = resolveSafely(root, projectName.trim());
        if (!Files.isDirectory(projectDir)) {
            throw new IllegalArgumentException("工程目录不存在: " + projectName
                    + "，可用目录见 listLocalProjects 返回的 dirName");
        }
        return projectDir;
    }

    /** 路径安全：解析后必须仍在根目录内，拒绝 ../ 越界与绝对路径逃逸 */
    private Path resolveSafely(Path root, String relative) {
        Path resolved = root.resolve(relative).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("非法路径（超出代码根目录）: " + relative);
        }
        return resolved;
    }

    /** 遍历文件，跳过 SKIP_DIRS 与不可读项，访问文件数超限后终止 */
    private void walkFiles(Path start, java.util.function.Consumer<Path> consumer) throws IOException {
        final int[] visited = {0};
        Files.walkFileTree(start, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(start) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (visited[0]++ >= MAX_VISIT_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                consumer.accept(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 读取文本；NUL 嗅探识别二进制，UTF-8 容错解码（GBK 中文注释不会抛异常） */
    private String readText(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
                if (bytes[i] == 0) {
                    return null;
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Pattern compilePattern(String keyword, boolean useRegex) {
        if (useRegex) {
            // RE2 线性时间引擎：模型给的正则不受输入长度回溯影响，杜绝 ReDoS 占死线程
            return Pattern.compile(keyword);
        }
        return Pattern.compile(Pattern.quote(keyword.trim()));
    }

    private static String normalizeExt(String fileExt) {
        if (fileExt == null || fileExt.isBlank()) {
            return null;
        }
        String ext = fileExt.trim().toLowerCase();
        return ext.startsWith(".") ? ext : "." + ext;
    }

    private int clampMatches(Integer maxResults) {
        int max = properties.getMaxMatches() == null ? 50 : properties.getMaxMatches();
        if (maxResults == null) {
            return max;
        }
        return Math.max(1, Math.min(max, maxResults));
    }

    private static int clampLine(Integer value, int defaultValue, int max) {
        if (value == null || value < 1) {
            return Math.min(defaultValue, max);
        }
        return Math.min(value, max);
    }

    private boolean fileSizeOver(Path file) {
        return fileSizeBytes(file) > properties.getMaxFileBytes();
    }

    private static long fileSizeBytes(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 相对路径（相对传入的根目录，而非固定 root-path，支持环境副本目录） */
    private String relativePath(Path path, Path root) {
        String rootString = root.toString();
        String pathString = path.toAbsolutePath().normalize().toString();
        if (pathString.equals(rootString)) {
            return ".";
        }
        return pathString.startsWith(rootString + "/")
                ? pathString.substring(rootString.length() + 1)
                : pathString;
    }

    private static String truncateLine(String line) {
        String trimmed = line.trim();
        return trimmed.length() <= MAX_LINE_CHARS ? trimmed : trimmed.substring(0, MAX_LINE_CHARS) + "...";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 口语关键词匹配：文本包含任一关键词（忽略大小写）即命中 */
    private static <T> List<T> matchByKeywords(String text, List<T> entries,
                                               java.util.function.Function<T, List<String>> keywordsFn) {
        List<T> matched = new ArrayList<>();
        if (text == null || text.isBlank() || entries == null) {
            return matched;
        }
        String normalized = text.toLowerCase();
        for (T entry : entries) {
            List<String> keywords = keywordsFn.apply(entry);
            if (keywords == null) {
                continue;
            }
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase())) {
                    matched.add(entry);
                    break;
                }
            }
        }
        return matched;
    }

    /** CLS 主题注册是否存在（跨站点；路由只判 clsRegistered，不消费 topicId） */
    private boolean clsTopicRegistered(String clsKey) {
        if (clsProperties == null || clsKey == null) {
            return false;
        }
        for (ClsProperties.Site site : clsProperties.getSites().values()) {
            if (site.getTopics() != null && site.getTopics().containsKey(clsKey)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 工具参数/返回 DTO ====================

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeSearchRequest {
        @JsonProperty(value = "environment")
        @JsonPropertyDescription("环境名（以 resolveRouting 返回为准，如 生产/qa），各环境代码副本分支不同，必须传对，否则搜到错误分支的代码；不确定时先 resolveRouting")
        private String environment;

        @JsonProperty(value = "keyword")
        @JsonPropertyDescription("搜索关键词（默认整串精确匹配，不是分词）或正则表达式（useRegex=true 时），如: OrderController、下单失败、ERR_1001")
        private String keyword;

        @JsonProperty(value = "useRegex")
        @JsonPropertyDescription("keyword 是否按正则表达式解析，默认 false（纯文本匹配）。正则非法会返回错误")
        private Boolean useRegex;

        @JsonProperty(value = "projectName")
        @JsonPropertyDescription("限定搜索的工程目录名（listLocalProjects 返回的 dirName，如 hospital-admin-api-web），不传则搜索整个代码根目录（较慢，尽量传）")
        private String projectName;

        @JsonProperty(value = "fileExt")
        @JsonPropertyDescription("按扩展名过滤，如 java/xml/yml/properties/js/sql/vue，不传则搜索所有文本文件")
        private String fileExt;

        @JsonProperty(value = "maxResults")
        @JsonPropertyDescription("最多返回命中条数，默认50")
        private Integer maxResults;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeFileReadRequest {
        @JsonProperty(value = "environment")
        @JsonPropertyDescription("环境名，与 searchCode/findFiles 时的 environment 保持一致")
        private String environment;

        @JsonProperty(value = "filePath")
        @JsonPropertyDescription("相对代码根目录的文件路径，用 searchCode/findFiles 返回的 file 字段原样传入")
        private String filePath;

        @JsonProperty(value = "startLine")
        @JsonPropertyDescription("起始行号（1开始），如异常栈给出 Foo.java:120 则传 40 并把 endLine 传 200 看上下文；不传从第1行读")
        private Integer startLine;

        @JsonProperty(value = "endLine")
        @JsonPropertyDescription("结束行号（含），单次最多返回约150行，超出自动截断；长文件分段读取")
        private Integer endLine;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileFindRequest {
        @JsonProperty(value = "environment")
        @JsonPropertyDescription("环境名（以 resolveRouting 返回为准），必须与后续 readCodeFile 一致")
        private String environment;

        @JsonProperty(value = "fileNamePattern")
        @JsonPropertyDescription("文件名正则表达式，如 .*OrderController.* 、^application.*\\.yml$")
        private String fileNamePattern;

        @JsonProperty(value = "projectName")
        @JsonPropertyDescription("限定的工程目录名，不传则在整个代码根目录中查找")
        private String projectName;

        @JsonProperty(value = "maxResults")
        @JsonPropertyDescription("最多返回条数，默认50")
        private Integer maxResults;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProjectListResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("调用是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "rootPath")
        @JsonPropertyDescription("本地代码根目录")
        private String rootPath;

        @JsonProperty(value = "projects")
        @JsonPropertyDescription("工程列表")
        private List<ProjectInfo> projects;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ProjectInfo {
        @JsonProperty(value = "dirName")
        @JsonPropertyDescription("工程目录名，作为 projectName 参数使用")
        private String dirName;

        @JsonProperty(value = "description")
        @JsonPropertyDescription("工程说明（对应业务系统）")
        private String description;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeSearchResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("搜索是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "environment")
        @JsonPropertyDescription("本次实际读取的环境名，null=默认目录（root-path）")
        private String environment;

        @JsonProperty(value = "branch")
        @JsonPropertyDescription("本次读取代码副本对应的 git 分支；引用代码结论时注明该分支")
        private String branch;

        @JsonProperty(value = "searchRoot")
        @JsonPropertyDescription("本次搜索的范围（工程目录或根目录）")
        private String searchRoot;

        @JsonProperty(value = "scannedFiles")
        @JsonPropertyDescription("扫描的文件数")
        private Integer scannedFiles;

        @JsonProperty(value = "returned")
        @JsonPropertyDescription("返回的命中条数")
        private Integer returned;

        @JsonProperty(value = "truncated")
        @JsonPropertyDescription("是否因达到 maxResults 截断；true 时可缩小范围或加 fileExt 继续搜")
        private Boolean truncated;

        @JsonProperty(value = "matches")
        @JsonPropertyDescription("命中列表：file 相对路径 + lineNo 行号 + lineText 行内容")
        private List<CodeMatch> matches;

        @JsonProperty(value = "notes")
        @JsonPropertyDescription("提示信息：环境未识别/目录降级等，需要注意")
        private List<String> notes;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeMatch {
        @JsonProperty(value = "file")
        @JsonPropertyDescription("相对代码根目录的文件路径，可作为 readCodeFile 的 filePath")
        private String file;

        @JsonProperty(value = "lineNo")
        @JsonPropertyDescription("命中行号（1开始）")
        private Integer lineNo;

        @JsonProperty(value = "lineText")
        @JsonPropertyDescription("命中行内容（已截断）")
        private String lineText;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeFileReadResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("读取是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "environment")
        @JsonPropertyDescription("本次实际读取的环境名，null=默认目录（root-path）")
        private String environment;

        @JsonProperty(value = "branch")
        @JsonPropertyDescription("本次读取代码副本对应的 git 分支；文件在其他环境副本找到时以 notes 说明")
        private String branch;

        @JsonProperty(value = "filePath")
        @JsonPropertyDescription("实际读取的文件相对路径")
        private String filePath;

        @JsonProperty(value = "startLine")
        @JsonPropertyDescription("本次返回的起始行号")
        private Integer startLine;

        @JsonProperty(value = "endLine")
        @JsonPropertyDescription("本次返回的结束行号（含）")
        private Integer endLine;

        @JsonProperty(value = "totalLines")
        @JsonPropertyDescription("文件总行数")
        private Integer totalLines;

        @JsonProperty(value = "truncated")
        @JsonPropertyDescription("是否未读到文件末尾；true 时把 startLine 换成 endLine+1 继续读")
        private Boolean truncated;

        @JsonProperty(value = "content")
        @JsonPropertyDescription("文件内容，每行格式为 行号\\t内容")
        private String content;

        @JsonProperty(value = "notes")
        @JsonPropertyDescription("提示信息：环境未识别/文件在其他环境副本找到等")
        private List<String> notes;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileFindResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("查找是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "environment")
        @JsonPropertyDescription("本次实际读取的环境名，null=默认目录（root-path）")
        private String environment;

        @JsonProperty(value = "branch")
        @JsonPropertyDescription("本次读取代码副本对应的 git 分支")
        private String branch;

        @JsonProperty(value = "searchRoot")
        @JsonPropertyDescription("本次查找的范围")
        private String searchRoot;

        @JsonProperty(value = "returned")
        @JsonPropertyDescription("返回条数")
        private Integer returned;

        @JsonProperty(value = "truncated")
        @JsonPropertyDescription("是否因达到 maxResults 截断")
        private Boolean truncated;

        @JsonProperty(value = "files")
        @JsonPropertyDescription("命中的文件列表：file 相对路径 + sizeBytes 大小")
        private List<FileHit> files;

        @JsonProperty(value = "notes")
        @JsonPropertyDescription("提示信息：环境未识别/目录降级等")
        private List<String> notes;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FileHit {
        @JsonProperty(value = "file")
        @JsonPropertyDescription("相对代码根目录的文件路径")
        private String file;

        @JsonProperty(value = "sizeBytes")
        @JsonPropertyDescription("文件大小（字节）")
        private Long sizeBytes;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoutingRequest {
        @JsonProperty(value = "environment")
        @JsonPropertyDescription("环境口语描述，如: 生产、预发、qa（以 troubleshoot.code-analysis.routing.environments 配置为准）；用户没说可不传")
        private String environment;

        @JsonProperty(value = "businessLine")
        @JsonPropertyDescription("业务线口语描述，如: 订单、支付、用户；用户没明确说可不传")
        private String businessLine;

        @JsonProperty(value = "symptom")
        @JsonPropertyDescription("问题现象原文（如: 用户下单失败/支付回调没到账），业务线没说时用它兜底识别")
        private String symptom;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoutingResult {
        @JsonProperty(value = "success")
        @JsonPropertyDescription("解析是否成功")
        private Boolean success;

        @JsonProperty(value = "errorMsg")
        @JsonPropertyDescription("失败原因，成功时为空")
        private String errorMsg;

        @JsonProperty(value = "environments")
        @JsonPropertyDescription("命中的环境列表")
        private List<EnvInfo> environments;

        @JsonProperty(value = "candidates")
        @JsonPropertyDescription("路由候选列表，每项含 CLS 应用与本地工程；clsRegistered=false 时该应用暂无法查日志")
        private List<RoutingCandidate> candidates;

        @JsonProperty(value = "notes")
        @JsonPropertyDescription("提示信息：未识别区域/业务线、区域日志渠道不可用等")
        private List<String> notes;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EnvInfo {
        @JsonProperty(value = "name")
        @JsonPropertyDescription("环境名")
        private String name;

        @JsonProperty(value = "matched")
        @JsonPropertyDescription("是否由用户话术命中（false=未识别列出的全量候选）")
        private Boolean matched;

        @JsonProperty(value = "codeBranch")
        @JsonPropertyDescription("该环境代码副本对应的 git 分支，后续 searchCode/readCodeFile 传 environment 即读取该副本")
        private String codeBranch;

        @JsonProperty(value = "note")
        @JsonPropertyDescription("环境说明")
        private String note;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoutingCandidate {
        @JsonProperty(value = "environment")
        @JsonPropertyDescription("环境名")
        private String environment;

        @JsonProperty(value = "businessLine")
        @JsonPropertyDescription("业务线名")
        private String businessLine;

        @JsonProperty(value = "station")
        @JsonPropertyDescription("站点/服务名，如: 订单服务、支付网关")
        private String station;

        @JsonProperty(value = "role")
        @JsonPropertyDescription("该站点在本业务线中的职责")
        private String role;

        @JsonProperty(value = "clsAppName")
        @JsonPropertyDescription("腾讯云CLS应用名（queryClsLog 的 appNames 参数直接用它；null=该应用无CLS主题）")
        private String clsAppName;

        @JsonProperty(value = "clsRegistered")
        @JsonPropertyDescription("该应用的腾讯云CLS主题是否已配置（false=CLS日志暂无法查询）")
        private Boolean clsRegistered;

        @JsonProperty(value = "localProject")
        @JsonPropertyDescription("本地工程目录名（searchCode 的 projectName 参数）")
        private String localProject;

        @JsonProperty(value = "codeBranch")
        @JsonPropertyDescription("该环境代码副本的 git 分支；searchCode/readCodeFile/findFiles 传 environment 参数即读对应副本")
        private String codeBranch;

        @JsonProperty(value = "searchHints")
        @JsonPropertyDescription("建议搜索的代码位置/关键词提示")
        private String searchHints;
    }

}
