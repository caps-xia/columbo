package org.caps.columbo.domain.agent.service.armory.matter.tools;

import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库工具：检索打分/读取截断/速记写入/路径逃逸防护
 */
class KnowledgeToolsTest {

    @TempDir
    Path tempDir;

    private KnowledgeTools tools;

    private static KnowledgeTools.KnowledgeSearchRequest search(List<String> keywords) {
        KnowledgeTools.KnowledgeSearchRequest request = new KnowledgeTools.KnowledgeSearchRequest();
        request.setKeywords(keywords);
        return request;
    }

    private static KnowledgeTools.KnowledgeReadRequest read(String path) {
        KnowledgeTools.KnowledgeReadRequest request = new KnowledgeTools.KnowledgeReadRequest();
        request.setRelativePath(path);
        return request;
    }

    private static KnowledgeTools.KnowledgeAppendRequest append(String title, String content) {
        KnowledgeTools.KnowledgeAppendRequest request = new KnowledgeTools.KnowledgeAppendRequest();
        request.setTitle(title);
        request.setContent(content);
        return request;
    }

    @BeforeEach
    void setUp() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setRootPath(tempDir.toString());
        properties.setWriteEnabled(true);
        tools = new KnowledgeTools();
        org.springframework.test.util.ReflectionTestUtils.setField(tools, "properties", properties);

        Files.writeString(tempDir.resolve("慢SQL排查.md"),
                "---\ntags: [踩坑记录]\n---\n# 慢SQL\n订单列表接口超时，EXPLAIN 发现全表扫描，缺索引 idx_user。\n## 解决方案\n加索引后 200ms。");
        Files.createDirectories(tempDir.resolve("10-Projects"));
        Files.writeString(tempDir.resolve("10-Projects/下单失败攻关.md"),
                "# 下单失败\n库存扣减为 0 未拦截，导致下单直接失败。补了库存校验。");
        Files.createDirectories(tempDir.resolve("attachments"));
        Files.writeString(tempDir.resolve("attachments/pic.md"), "附件目录不参与检索");
        Files.writeString(tempDir.resolve("无关笔记.md"), "今天天气不错，午饭吃了面。");
    }

    @Test
    void searchHitsByBodyKeyword() {
        KnowledgeTools.KnowledgeSearchResult result =
                tools.searchKnowledge(search(List.of("库存扣减")));
        assertTrue(result.getSuccess());
        assertEquals(1, result.getTotalHits());
        assertEquals("10-Projects/下单失败攻关.md", result.getHits().get(0).getRelativePath());
        assertNotNull(result.getHits().get(0).getMatchedLines());
    }

    @Test
    void titleHitOutranksBodyHit() {
        KnowledgeTools.KnowledgeSearchResult result =
                tools.searchKnowledge(search(List.of("慢sql")));
        assertEquals("慢SQL排查.md", result.getHits().get(0).getRelativePath());
    }

    @Test
    void searchSkipsAttachmentsAndReportsNoHit() {
        KnowledgeTools.KnowledgeSearchResult result =
                tools.searchKnowledge(search(List.of("附件目录")));
        assertTrue(result.getSuccess());
        assertEquals(0, result.getTotalHits());
        assertNull(result.getHits());
        assertNotNull(result.getHint());
    }

    @Test
    void readReturnsContent() {
        KnowledgeTools.KnowledgeReadResult result =
                tools.readKnowledgeNote(read("10-Projects/下单失败攻关.md"));
        assertTrue(result.getSuccess());
        assertTrue(result.getContent().contains("库存扣减为 0"));
        assertFalse(result.getTruncated());
    }

    @Test
    void readRejectsPathEscape() {
        KnowledgeTools.KnowledgeReadResult result =
                tools.readKnowledgeNote(read("../etc/passwd"));
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("越界"));
    }

    @Test
    void appendWritesToInboxWithDatePrefix() {
        KnowledgeTools.KnowledgeAppendResult result = tools.appendKnowledgeNote(append("下单失败-库存扣减为0未拦截", "## 现象\n下单报错\n## 根因\n未校验库存"));
        assertTrue(result.getSuccess());
        assertTrue(result.getWrittenPath().startsWith("00-Inbox/速记-"));
        assertTrue(result.getWrittenPath().endsWith("下单失败-库存扣减为0未拦截.md"));
        assertTrue(Files.exists(tempDir.resolve(result.getWrittenPath())));
    }

    @Test
    void appendDeduplicatesAndSanitizesFilename() {
        tools.appendKnowledgeNote(append("重复问题", "内容"));
        KnowledgeTools.KnowledgeAppendResult second =
                tools.appendKnowledgeNote(append("重复问题", "内容2"));
        assertTrue(second.getSuccess());
        assertTrue(second.getWrittenPath().contains("重复问题-1.md"));

        KnowledgeTools.KnowledgeAppendResult evil =
                tools.appendKnowledgeNote(append("a/b:c*d", "x"));
        assertTrue(evil.getSuccess());
        assertFalse(evil.getWrittenPath().contains("/b"));
    }

    @Test
    void appendRejectedWhenDisabled() {
        KnowledgeProperties disabled = new KnowledgeProperties();
        disabled.setRootPath(tempDir.toString());
        disabled.setWriteEnabled(false);
        KnowledgeTools locked = new KnowledgeTools();
        org.springframework.test.util.ReflectionTestUtils.setField(locked, "properties", disabled);
        KnowledgeTools.KnowledgeAppendResult result =
                locked.appendKnowledgeNote(append("t", "c"));
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("未开启"));
    }

    @Test
    void appendAutoPrependsFrontmatterWithNormalizedSystem() throws Exception {
        KnowledgeTools.KnowledgeAppendResult result =
                tools.appendKnowledgeNote(append("下单失败-库存扣减为0未拦截", "## 现象\n下单报错"));
        assertTrue(result.getSuccess());
        String written = Files.readString(tempDir.resolve(result.getWrittenPath()));
        // 与库内 AGENTS.md 速记写法对齐：tags/date/system 头自动补齐，system 归一化到合法枚举
        assertTrue(written.startsWith("---\ntags: [排查速记]\ndate: \""));
        assertTrue(written.contains("\nsystem: 公共平台\n---\n\n## 现象"));

        KnowledgeTools.KnowledgeAppendRequest typed = append("订单重复发货", "## 现象\n重复");
        typed.setSystem("消费医疗");
        KnowledgeTools.KnowledgeAppendResult typedResult = tools.appendKnowledgeNote(typed);
        assertTrue(Files.readString(tempDir.resolve(typedResult.getWrittenPath())).contains("system: 消费医疗"));
    }

    @Test
    void appendKeepsExistingFrontmatterWithoutDoubling() throws Exception {
        KnowledgeTools.KnowledgeAppendResult result = tools.appendKnowledgeNote(
                append("自带头的速记", "---\ntags: [排查速记]\ndate: \"2026-09-15\"\nsystem: 严肃医疗\n---\n\n正文"));
        assertTrue(result.getSuccess());
        String written = Files.readString(tempDir.resolve(result.getWrittenPath()));
        assertEquals(1, written.split("\n---\n", -1).length - 1);
        assertTrue(written.endsWith("正文"));
    }

    @Test
    void rootNotConfiguredReportsClearly() {
        KnowledgeProperties empty = new KnowledgeProperties();
        KnowledgeTools unconfigured = new KnowledgeTools();
        org.springframework.test.util.ReflectionTestUtils.setField(unconfigured, "properties", empty);
        KnowledgeTools.KnowledgeSearchResult result =
                unconfigured.searchKnowledge(search(List.of("任意")));
        assertFalse(result.getSuccess());
        assertTrue(result.getErrorMsg().contains("未配置"));
    }
}
