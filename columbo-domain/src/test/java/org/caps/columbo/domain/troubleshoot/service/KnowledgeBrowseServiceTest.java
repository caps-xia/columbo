package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeFileVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeHitVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeNodeVO;
import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库浏览：frontmatter 解析 / 目录树（跳过附件目录）/ 路径穿越拦截 / 关键词搜索
 */
class KnowledgeBrowseServiceTest {

    @TempDir
    Path root;

    private KnowledgeBrowseService service;

    @BeforeEach
    void setUp() throws Exception {
        KnowledgeProperties properties = new KnowledgeProperties();
        properties.setRootPath(root.toString());
        service = new KnowledgeBrowseService();
        ReflectionTestUtils.setField(service, "properties", properties);

        Files.writeString(root.resolve("index.md"), "# 目录\n\n- 00-Inbox 收集箱");
        Files.createDirectories(root.resolve("00-Inbox"));
        Files.writeString(root.resolve("00-Inbox/速记-20260922-下单失败.md"), """
                ---
                tags: [排查速记, 消费医疗]
                date: "2026-09-22"
                system: 消费医疗
                ---

                # 下单失败-库存为0未拦截

                ## 现象
                用户下单失败，提示库存不足。
                """);
        Files.createDirectories(root.resolve("10-Systems/消费医疗"));
        Files.writeString(root.resolve("10-Systems/消费医疗/速记-20260913-瑞慈体检.md"), "# 瑞慈体检预约失败\n\n机构容量已用完。");
        Files.createDirectories(root.resolve("attachments"));
        Files.writeString(root.resolve("attachments/ignore.md"), "# 不该出现");
    }

    @Test
    void treeSkipsAttachmentsAndSortsDirsFirst() {
        List<KnowledgeNodeVO> tree = service.tree();
        assertEquals("00-Inbox", tree.get(0).getName());
        assertEquals("10-Systems", tree.get(1).getName());
        assertEquals("index.md", tree.get(tree.size() - 1).getName());
        // attachments 被跳过
        assertFalse(tree.stream().anyMatch(node -> node.getName().equals("attachments")));
        // 子目录递归
        assertEquals(1, tree.get(1).getChildren().get(0).getChildren().size());
    }

    @Test
    void readParsesFrontmatterAndStripsIt() {
        KnowledgeFileVO vo = service.read("00-Inbox/速记-20260922-下单失败.md");
        assertEquals("下单失败-库存为0未拦截", vo.getTitle());
        assertEquals("消费医疗", vo.getSystem());
        assertEquals("2026-09-22", vo.getDate());
        assertEquals(List.of("排查速记", "消费医疗"), vo.getTags());
        assertTrue(vo.getContent().startsWith("# 下单失败"));
        assertFalse(vo.getContent().contains("frontmatter"), "正文不应包含 frontmatter 块");
    }

    @Test
    void readFallsBackToFilenameTitleWithoutHeading() {
        KnowledgeFileVO vo = service.read("index.md");
        assertEquals("目录", vo.getTitle());
    }

    @Test
    void pathTraversalRejected() {
        assertThrows(AppException.class, () -> service.read("../outside.md"));
        assertThrows(AppException.class, () -> service.read("../../etc/passwd.md"));
        assertThrows(AppException.class, () -> service.read("not-exist.md"));
        assertThrows(AppException.class, () -> service.read("index.md/../../escape.md"));
    }

    @Test
    void searchHitsBodyWithLineNoAndTitle() {
        List<KnowledgeHitVO> hits = service.search("库存不足");
        assertEquals(1, hits.size());
        assertEquals("00-Inbox/速记-20260922-下单失败.md", hits.get(0).getPath());
        assertEquals("下单失败-库存为0未拦截", hits.get(0).getTitle());
        assertNotNull(hits.get(0).getLineNo());
        assertTrue(hits.get(0).getSnippet().contains("库存不足"));
        // 文件名命中
        List<KnowledgeHitVO> byName = service.search("瑞慈体检");
        assertEquals("10-Systems/消费医疗/速记-20260913-瑞慈体检.md", byName.get(0).getPath());
    }
}
