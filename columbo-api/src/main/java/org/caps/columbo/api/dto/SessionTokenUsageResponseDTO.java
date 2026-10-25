package org.caps.columbo.api.dto;

import lombok.Data;

/**
 * 会话累计 token 用量（成本记账）：cachedTokens 是提示缓存命中部分（计入 inputTokens），
 * 真实计费输入 = inputTokens - cachedTokens。
 *
 * @author caps
 */
@Data
public class SessionTokenUsageResponseDTO {

    /** 累计输入 token（含缓存命中） */
    private long inputTokens;

    /** 累计输出 token */
    private long outputTokens;

    /** 其中提示缓存命中的 token */
    private long cachedTokens;

    /** 累计总 token */
    private long totalTokens;

    /** 模型调用次数 */
    private long llmCalls;
}
