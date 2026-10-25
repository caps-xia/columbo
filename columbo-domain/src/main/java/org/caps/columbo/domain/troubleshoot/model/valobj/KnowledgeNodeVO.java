package org.caps.columbo.domain.troubleshoot.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识库目录树节点（web 端知识库页浏览用）
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class KnowledgeNodeVO {

    /** 目录/文件名（如: 00-Inbox、速记-20260922-xx.md） */
    private String name;

    /** 相对根目录的路径（/ 分隔，web 端读取/高亮用） */
    private String path;

    /** dir=目录 file=文件 */
    private String type;

    /** 文件大小（字节，目录为空） */
    private Long size;

    /** 最后修改时间（毫秒时间戳，目录为空） */
    private Long modifiedAt;

    /** 子节点（目录才有，按 目录在前+名称排序） */
    private List<KnowledgeNodeVO> children;
}
