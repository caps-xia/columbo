package org.caps.columbo.trigger.http;

import org.caps.columbo.api.response.Response;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeFileVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeHitVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.KnowledgeNodeVO;
import org.caps.columbo.domain.troubleshoot.service.KnowledgeBrowseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识库浏览（web 端知识库页）：目录树 / 读笔记 / 搜索，全部只读。
 * 写入仍由排查助手速记与定时分拣负责，web 端不提供编辑。
 */
@Slf4j
@Tag(name = "智能体服务", description = "本地知识库浏览")
@RestController
@RequestMapping("/api/v1/knowledge/")
public class KnowledgeBrowseController {

    @Resource
    private KnowledgeBrowseService knowledgeBrowseService;

    @Operation(summary = "知识库目录树", description = "目录在前按名称排序；跳过附件/模板/隐藏目录")
    @GetMapping("tree")
    public Response<List<KnowledgeNodeVO>> tree() {
        return Response.success(knowledgeBrowseService.tree());
    }

    @Operation(summary = "读取笔记", description = "frontmatter 解析为字段，正文为去掉 frontmatter 的 markdown")
    @GetMapping("file")
    public Response<KnowledgeFileVO> file(@RequestParam String path) {
        return Response.success(knowledgeBrowseService.read(path));
    }

    @Operation(summary = "搜索笔记", description = "文件名/标题命中优先，正文逐行命中带片段与行号，上限 30 条")
    @GetMapping("search")
    public Response<List<KnowledgeHitVO>> search(@RequestParam String keyword) {
        return Response.success(knowledgeBrowseService.search(keyword));
    }
}
