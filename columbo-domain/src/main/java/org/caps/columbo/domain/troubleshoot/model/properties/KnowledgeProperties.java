package org.caps.columbo.domain.troubleshoot.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地知识库配置（排查经验库：检索历史案例 + 沉淀新速记，形成"越用越聪明"闭环）。
 */
@Data
@ConfigurationProperties(prefix = "troubleshoot.knowledge", ignoreInvalidFields = true)
public class KnowledgeProperties {

    /** 知识库根目录（如 Obsidian/PARA 笔记库），搜索/读取都限制在该目录内 */
    private String rootPath = "";

    /** 是否允许写入速记（只写 00-Inbox 白名单子目录；false 时沉淀工具明确报错） */
    private Boolean writeEnabled = true;

    /** searchKnowledge 单次返回的最大命中篇数 */
    private Integer maxResults = 5;

    /** 单文件读取大小上限（字节），超过截断 */
    private Integer maxFileBytes = 262144;

    /** 速记落点子目录（相对 root-path，PARA 工作流的收件箱） */
    private String inboxDir = "00-Inbox";

    /** RAG 向量检索开关（提炼自 waliapi 知识库混合检索）：关闭或嵌入配置缺失时自动降级纯关键词 */
    private Boolean vectorSearchEnabled = true;

    /** 嵌入接口完整地址（OpenAI 兼容 /v1/embeddings，走公司 AI 网关） */
    private String embeddingsUrl = "";

    /** 嵌入接口密钥（建议环境变量注入） */
    private String embeddingsApiKey = "";

    /** 嵌入模型名（按网关实际支持调整；调用失败自动降级纯关键词检索） */
    private String embeddingsModel = "embedding-3";

    /** 单文件分块上限（字符），超过按段落再切 */
    private Integer chunkMaxChars = 1200;
}
