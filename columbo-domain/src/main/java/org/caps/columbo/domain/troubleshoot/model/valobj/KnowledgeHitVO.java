package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库搜索命中（web 端）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeHitVO {

    /** 笔记相对路径 */
    private String path;

    /** 标题（首个 # 标题或文件名） */
    private String title;

    /** 命中行号（正文按 1 起） */
    private Integer lineNo;

    /** 命中行片段（截断） */
    private String snippet;
}
