package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库笔记内容（web 端渲染用）：frontmatter 解析成独立字段，正文为去掉 frontmatter 的 markdown
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeFileVO {

    /** 相对路径 */
    private String path;

    /** 标题：首个 # 标题，缺失时用文件名（去 .md）兜底 */
    private String title;

    /** frontmatter tags */
    private List<String> tags;

    /** frontmatter date（yyyy-MM-dd） */
    private String date;

    /** frontmatter system（严肃医疗/消费医疗/公共平台） */
    private String system;

    /** 正文（不含 frontmatter） */
    private String content;
}
