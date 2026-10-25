package org.caps.columbo.domain.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.caps.columbo.domain.agent.adapter.port.IEmbeddingPort;
import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 知识库向量检索服务（提炼自 waliapi 知识库 RAG，按 tars 规模裁剪）：
 * <ul>
 *   <li>持久化：知识库目录旁 {@code .vectors.json} sidecar（点开头，浏览/关键词检索自动跳过），
 *       与笔记库同生命周期——拷贝库即连向量一起带走，无需独立向量数据库；</li>
 *   <li>增量入库：按 mtime+size 对比，仅对新增/变更文件重新分块嵌入；删除文件自动清理；</li>
 *   <li>检索：暴力余弦（千级向量毫秒级，规模足够）+ topK 返回，融合排序交给调用方（RRF）；</li>
 *   <li>嵌入调用经 {@link IEmbeddingPort} 端口（实现在 infrastructure）——本类只含分块/比对/缓存领域逻辑；</li>
 *   <li>降级：嵌入接口失败时返回空结果，调用方退回纯关键词检索——RAG 永不阻断主流程。</li>
 * </ul>
 */
@Slf4j
@Component
public class VectorKnowledgeService {

    /** 单文件分块上限（条），防异常大文件刷爆嵌入调用 */
    private static final int MAX_CHUNKS_PER_FILE = 20;

    @Resource
    private KnowledgeProperties properties;

    /** 预热走全局业务线程池（统一治理 + MDC 透传），不再裸建线程 */
    @Resource(name = "bizExecutor")
    private Executor bizExecutor;

    /** 嵌入网关端口：HTTP/鉴权细节在 infrastructure 实现，领域只拿向量 */
    @Resource
    private IEmbeddingPort embeddingPort;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, FileEntry> index;
    private boolean loaded;

    /** 熔断：嵌入网关明确不支持 Embeddings（404）时置位，本次运行内停用向量检索，避免逐文件 WARN 刷屏 */
    private volatile boolean embedUnavailable;

    /** 启动后异步预热索引（失败只告警，检索时还会懒加载） */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled()) {
            return;
        }
        bizExecutor.execute(() -> {
            try {
                refresh();
                log.info("知识库向量索引预热完成 files={}", index == null ? 0 : index.size());
            } catch (Exception e) {
                log.warn("知识库向量索引预热失败（检索降级为纯关键词）: {}", e.getMessage());
            }
        });
    }

    /** 向量语义检索：返回 path+原文片段 命中（按相似度降序）；任何失败返回空（调用方降级关键词） */
    public List<VectorHit> search(String query, int topK) {
        if (!enabled() || embedUnavailable || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            synchronized (this) {
                refresh();
            }
            if (index == null || index.isEmpty()) {
                return List.of();
            }
            double[] queryVector = embeddingPort.embed(List.of(query))[0];
            record Scored(String path, String chunk, double score) {
            }
            List<Scored> scored = new ArrayList<>();
            for (Map.Entry<String, FileEntry> entry : index.entrySet()) {
                for (Chunk chunk : entry.getValue().chunks) {
                    scored.add(new Scored(entry.getKey(), chunk.text, cosine(queryVector, chunk.vector)));
                }
            }
            scored.sort(Comparator.comparingDouble(Scored::score).reversed());
            List<VectorHit> hits = new ArrayList<>();
            for (Scored s : scored.subList(0, Math.min(Math.max(topK, 1), scored.size()))) {
                hits.add(new VectorHit(s.path(), s.chunk(), s.score()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("知识库向量检索失败，降级为纯关键词: {}", e.getMessage());
            return List.of();
        }
    }

    // ============ 内部实现 ============

    private boolean enabled() {
        return Boolean.TRUE.equals(properties.getVectorSearchEnabled())
                && properties.getEmbeddingsUrl() != null && !properties.getEmbeddingsUrl().isBlank();
    }

    /** 增量刷新：mtime/size 变化的文件重新分块嵌入，消失的文件清理（加锁串行） */
    public synchronized void refresh() {
        if (embedUnavailable) {
            return;
        }
        Path root = root();
        if (root == null) {
            return;
        }
        if (!loaded) {
            index = loadStore(root);
            loaded = true;
        }
        Set<String> seen = new java.util.HashSet<>();
        boolean dirty = false;
        try (var walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !inDotDir(root, p))::iterator) {
                String rel = root.relativize(file).toString().replace('\\', '/');
                seen.add(rel);
                FileEntry entry = index.get(rel);
                long mtime = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                if (entry != null && entry.mtime == mtime && entry.size == size) {
                    continue;
                }
                try {
                    List<String> chunks = chunkMarkdown(Files.readString(file));
                    if (chunks.isEmpty()) {
                        index.remove(rel);
                        dirty = true;
                        continue;
                    }
                    if (chunks.size() > MAX_CHUNKS_PER_FILE) {
                        chunks = chunks.subList(0, MAX_CHUNKS_PER_FILE);
                    }
                    double[][] vectors = embeddingPort.embed(chunks);
                    index.put(rel, new FileEntry(mtime, size, chunks, vectors));
                    dirty = true;
                } catch (IEmbeddingPort.EmbeddingNotSupportedException e) {
                    // 网关不支持 Embeddings：本次运行熔断，只打一条可操作的日志
                    embedUnavailable = true;
                    log.warn("嵌入网关不支持 Embeddings API，向量检索已停用（检索退化为纯关键词）。"
                            + "如需启用：将 troubleshoot.knowledge.embeddings-url 指向支持嵌入的服务"
                            + "（如智谱开放平台 /api/paas/v4/embeddings，模型 embedding-3）后重启");
                    return;
                } catch (Exception e) {
                    // 单文件嵌入失败（超时/5xx 等临时错误）：保留旧向量，下轮再试
                    log.warn("知识库文件嵌入失败 file={} err={}", rel, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("知识库向量刷新失败: {}", e.getMessage());
            return;
        }
        for (String path : List.copyOf(index.keySet())) {
            if (!seen.contains(path)) {
                index.remove(path);
                dirty = true;
            }
        }
        if (dirty) {
            saveStore(root);
        }
    }

    /** 分块：按 "## " 小节切，超长小节按段落再切 */
    static List<String> chunkMarkdown(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("## ") && current.length() > 0) {
                addChunk(chunks, current);
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        addChunk(chunks, current);
        return chunks;
    }

    private static void addChunk(List<String> chunks, StringBuilder current) {
        String text = current.toString().trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > 1500) {
            StringBuilder part = new StringBuilder();
            for (String para : text.split("\n\\s*\n")) {
                if (part.length() + para.length() > 1500 && part.length() > 0) {
                    chunks.add(part.toString().trim());
                    part = new StringBuilder();
                }
                part.append(para).append("\n\n");
            }
            if (!part.toString().isBlank()) {
                chunks.add(part.toString().trim());
            }
            return;
        }
        chunks.add(text);
    }

    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private Map<String, FileEntry> loadStore(Path root) {
        Path store = storePath(root);
        try {
            if (Files.isRegularFile(store)) {
                VectorStore parsed = objectMapper.readValue(Files.readString(store), VectorStore.class);
                if (parsed.files != null) {
                    return parsed.files;
                }
            }
        } catch (Exception e) {
            log.warn("向量存储读取失败（重建）: {}", e.getMessage());
        }
        return new java.util.LinkedHashMap<>();
    }

    private void saveStore(Path root) {
        Path store = storePath(root);
        Path tmp = store.resolveSibling(store.getFileName() + ".tmp");
        try {
            Files.createDirectories(store.getParent());
            Files.writeString(tmp, objectMapper.writeValueAsString(new VectorStore(index)));
            Files.move(tmp, store, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("向量存储写入失败: {}", e.getMessage());
        }
    }

    private Path storePath(Path root) {
        return root.resolve(".vectors.json");
    }

    private Path root() {
        String rootPath = properties.getRootPath();
        if (rootPath == null || rootPath.isBlank()) {
            return null;
        }
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root : null;
    }

    private boolean inDotDir(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** 向量检索命中 */
    public record VectorHit(String path, String chunk, double score) {
    }

    /** 持久化结构 */
    public static class VectorStore {
        public int version = 1;
        public Map<String, FileEntry> files;

        public VectorStore() {
        }

        public VectorStore(Map<String, FileEntry> files) {
            this.files = files;
        }
    }

    public static class FileEntry {
        public long mtime;
        public long size;
        public List<Chunk> chunks = new ArrayList<>();

        public FileEntry() {
        }

        FileEntry(long mtime, long size, List<String> texts, double[][] vectors) {
            this.mtime = mtime;
            this.size = size;
            for (int i = 0; i < texts.size(); i++) {
                this.chunks.add(new Chunk(texts.get(i), vectors[i]));
            }
        }
    }

    public static class Chunk {
        public String text;
        public double[] vector;

        public Chunk() {
        }

        Chunk(String text, double[] vector) {
            this.text = text;
            this.vector = vector;
        }
    }
}
