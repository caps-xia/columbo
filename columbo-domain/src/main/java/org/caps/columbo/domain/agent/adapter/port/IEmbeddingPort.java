package org.caps.columbo.domain.agent.adapter.port;

import java.util.List;

/**
 * 嵌入网关端口（OpenAI 兼容 /v1/embeddings）：领域侧只关心"文本 → 向量"，
 * HTTP 协议、鉴权、网关差异封装在基础设施实现（EmbeddingPortImpl）——
 * 领域内核不得直接持有 HTTP 客户端（COLA：domain 零技术依赖，ArchUnit 有对应守护）。
 *
 * @author caps
 */
public interface IEmbeddingPort {

    /**
     * 批量文本嵌入，返回与输入顺序一致的向量数组。
     *
     * @throws EmbeddingNotSupportedException 网关明确不支持 Embeddings API（调用方应熔断向量检索）
     * @throws IllegalStateException           网关返回异常（临时错误，调用方可下轮重试）
     */
    double[][] embed(List<String> texts);

    /** 嵌入网关明确不支持 Embeddings API（调用方据此熔断，避免逐文件刷 WARN） */
    class EmbeddingNotSupportedException extends RuntimeException {

        public EmbeddingNotSupportedException(String message) {
            super(message);
        }
    }
}
