package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeFileVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeHitVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeNodeVO;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 知识库浏览服务（web 端知识库页）：目录树 / 读笔记（frontmatter 解析）/ 关键词搜索。
 * 只读——写入仍由排查助手 appendKnowledgeNote 与定时分拣负责。
 * 安全：路径必须落在 root-path 内（防穿越）、仅 .md、大小受 max-file-bytes 限制。
 */
@Slf4j
@Component
public class KnowledgeBrowseService {

    /** 与 KnowledgeTools 的检索范围保持一致：附件/模板/回收等目录不展示不检索 */
    private static final java.util.Set<String> SKIP_DIRS = java.util.Set.of(
            "attachments", "templates", ".trash", ".obsidian", "node_modules", ".workbuddy", ".zettelkasten");

    /** 目录树最大深度（库为 顶层目录/系统/ 三层，留余量） */
    private static final int MAX_DEPTH = 4;

    /** 中文文件名排序（Collator 按拼音，比 String.compareTo 对中文友好） */
    private static final Comparator<Object> CHINESE_ORDER =
            java.text.Collator.getInstance(java.util.Locale.CHINA);

    /** 搜索命中上限 */
    private static final int MAX_HITS = 30;

    /** 搜索片段单行截断长度 */
    private static final int SNIPPET_MAX = 160;

    @Resource
    private KnowledgeProperties properties;

    /** 目录树：目录在前、同层按名称排序（00-Inbox/10-Systems/20-Patterns 天然有序） */
    public List<KnowledgeNodeVO> tree() {
        Path root = requireRoot();
        return childrenOf(root, root, 1);
    }

    /** 读笔记：frontmatter 拆成字段，正文去掉 frontmatter 块 */
    public KnowledgeFileVO read(String relativePath) {
        Path target = resolveMarkdownInside(relativePath);
        String raw;
        try {
            raw = Files.readString(target);
        } catch (IOException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "笔记读取失败: " + relativePath);
        }
        KnowledgeFileVO.KnowledgeFileVOBuilder builder = KnowledgeFileVO.builder()
                .path(normalizePath(root().relativize(target).toString()));
        String body = raw;
        if (raw.stripLeading().startsWith("---")) {
            String[] parts = raw.stripLeading().split("---", 3);
            if (parts.length >= 3) {
                String frontmatter = parts[1];
                body = parts[2].stripLeading();
                for (String line : frontmatter.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("tags:")) {
                        builder.tags(parseTags(trimmed.substring("tags:".length())));
                    } else if (trimmed.startsWith("date:")) {
                        builder.date(stripQuotes(trimmed.substring("date:".length()).trim()));
                    } else if (trimmed.startsWith("system:")) {
                        builder.system(stripQuotes(trimmed.substring("system:".length()).trim()));
                    }
                }
            }
        }
        return builder.title(titleOf(target, body)).content(body).build();
    }

    /** 关键词搜索：标题命中优先，其次正文逐行命中（片段+行号），命中上限 30 */
    public List<KnowledgeHitVO> search(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "请提供搜索关键词");
        }
        String needle = keyword.trim();
        Path root = requireRoot();
        List<KnowledgeHitVO> titleHits = new ArrayList<>();
        List<KnowledgeHitVO> bodyHits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root, MAX_DEPTH)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !skipped(p, root))
                    .forEach(path -> {
                        String rel = normalizePath(root.relativize(path).toString());
                        String fileName = path.getFileName().toString();
                        if (fileName.contains(needle)) {
                            titleHits.add(KnowledgeHitVO.builder()
                                    .path(rel).title(fileName.replace(".md", ""))
                                    .lineNo(null).snippet("文件名命中").build());
                        }
                        searchBody(path, rel, needle, bodyHits);
                    });
        } catch (IOException e) {
            throw new AppException(ResponseCode.B001.getCode(), "知识库搜索失败: " + e.getMessage());
        }
        List<KnowledgeHitVO> hits = new ArrayList<>();
        hits.addAll(titleHits);
        hits.addAll(bodyHits);
        return hits.size() > MAX_HITS ? new ArrayList<>(hits.subList(0, MAX_HITS)) : hits;
    }

    // ============ 内部实现 ============

    private void searchBody(Path path, String rel, String needle, List<KnowledgeHitVO> bodyHits) {
        try {
            List<String> lines = Files.readAllLines(path);
            String title = null;
            for (int i = 0; i < lines.size() && bodyHits.size() < MAX_HITS; i++) {
                String line = lines.get(i);
                if (title == null && line.startsWith("# ")) {
                    title = line.substring(2).trim();
                }
                if (line.contains(needle)) {
                    String snippet = line.trim();
                    if (snippet.length() > SNIPPET_MAX) {
                        snippet = snippet.substring(0, SNIPPET_MAX) + "…";
                    }
                    bodyHits.add(KnowledgeHitVO.builder()
                            .path(rel)
                            .title(title != null ? title : path.getFileName().toString().replace(".md", ""))
                            .lineNo(i + 1)
                            .snippet(snippet)
                            .build());
                }
            }
        } catch (IOException e) {
            log.warn("知识库搜索读文件失败 path={} err={}", rel, e.getMessage());
        }
    }

    private List<KnowledgeNodeVO> childrenOf(Path root, Path dir, int depth) {
        List<KnowledgeNodeVO> children = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.forEach(entry -> {
                String name = entry.getFileName().toString();
                if (name.startsWith(".") || SKIP_DIRS.contains(name)) {
                    return;
                }
                String rel = normalizePath(root.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    KnowledgeNodeVO.KnowledgeNodeVOBuilder builder = KnowledgeNodeVO.builder()
                            .name(name).path(rel).type("dir");
                    if (depth < MAX_DEPTH) {
                        builder.children(childrenOf(root, entry, depth + 1));
                    }
                    children.add(builder.build());
                } else if (name.endsWith(".md")) {
                    try {
                        children.add(KnowledgeNodeVO.builder()
                                .name(name).path(rel).type("file")
                                .size(Files.size(entry))
                                .modifiedAt(Files.getLastModifiedTime(entry).toMillis())
                                .build());
                    } catch (IOException ignored) {
                        // 大小/时间读失败的文件仍展示
                        children.add(KnowledgeNodeVO.builder().name(name).path(rel).type("file").build());
                    }
                }
            });
        } catch (IOException e) {
            log.warn("知识库目录读取失败 dir={} err={}", dir, e.getMessage());
        }
        children.sort(Comparator
                .comparing((KnowledgeNodeVO node) -> "dir".equals(node.getType()) ? 0 : 1)
                .thenComparing(KnowledgeNodeVO::getName, CHINESE_ORDER));
        return children;
    }

    private Path resolveMarkdownInside(String relativePath) {
        if (!StringUtils.hasText(relativePath) || !relativePath.endsWith(".md")) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "仅支持读取 .md 笔记");
        }
        Path root = requireRoot();
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "笔记不存在或路径非法: " + relativePath);
        }
        int maxBytes = properties.getMaxFileBytes() == null ? 262_144 : properties.getMaxFileBytes();
        try {
            if (Files.size(target) > maxBytes) {
                throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                        "笔记超过大小上限（" + maxBytes + " 字节），请在本地打开: " + relativePath);
            }
        } catch (IOException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "笔记读取失败: " + relativePath);
        }
        return target;
    }

    private static String titleOf(Path file, String body) {
        for (String line : body.split("\n", 20)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return file.getFileName().toString().replace(".md", "");
    }

    private static List<String> parseTags(String raw) {
        String value = stripQuotes(raw.trim());
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        return Stream.of(value.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String stripQuotes(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** Windows 分隔符归一为 /（web 端传回路径保持一致） */
    private static String normalizePath(String path) {
        return path.replace('\\', '/');
    }

    private boolean skipped(Path path, Path root) {
        for (Path part : root.relativize(path)) {
            String name = part.toString();
            if (name.startsWith(".") || SKIP_DIRS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private Path root() {
        String rootPath = properties.getRootPath();
        if (!StringUtils.hasText(rootPath)) {
            throw new AppException(ResponseCode.B002.getCode(), "知识库未配置（troubleshoot.knowledge.root-path）");
        }
        return Path.of(rootPath).toAbsolutePath().normalize();
    }

    private Path requireRoot() {
        Path root = root();
        if (!Files.isDirectory(root)) {
            throw new AppException(ResponseCode.B002.getCode(), "知识库目录不存在: " + root);
        }
        return root;
    }
}
