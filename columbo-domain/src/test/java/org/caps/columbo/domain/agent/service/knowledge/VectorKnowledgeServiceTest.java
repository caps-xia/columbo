package org.caps.columbo.domain.agent.service.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库向量服务纯函数：markdown 分块、余弦相似度（嵌入 HTTP 与增量入库依赖网关，不在单测范围）
 */
class VectorKnowledgeServiceTest {

    @Test
    void chunkMarkdownSplitsBySection() {
        String content = """
                # 速记标题
                开头段落。
                ## 现象
                门店列表为空。
                ## 根因
                商品未覆盖该城市，SQL 过滤直接查空。
                """;
        List<String> chunks = VectorKnowledgeService.chunkMarkdown(content);
        assertEquals(3, chunks.size());
        assertTrue(chunks.get(1).startsWith("## 现象"));
        assertTrue(chunks.get(2).contains("根因"));
    }

    @Test
    void longContentSplitsByParagraph() {
        StringBuilder content = new StringBuilder("# 标题\n");
        for (int i = 0; i < 80; i++) {
            content.append("第").append(i).append("段推理内容，足够长以触发分块逻辑。第").append(i).append("段补充。\n\n");
        }
        List<String> chunks = VectorKnowledgeService.chunkMarkdown(content.toString());
        assertTrue(chunks.size() > 1);
        chunks.forEach(c -> assertTrue(c.length() <= 1600));
    }

    @Test
    void cosineComputesSimilarity() {
        assertEquals(1.0, VectorKnowledgeService.cosine(new double[]{1, 0}, new double[]{2, 0}), 1e-9);
        assertEquals(0.0, VectorKnowledgeService.cosine(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
        assertEquals(0.0, VectorKnowledgeService.cosine(new double[]{1, 0}, null), 1e-9);
        assertTrue(VectorKnowledgeService.cosine(new double[]{1, 1}, new double[]{1, 0}) > 0.7);
    }
}
