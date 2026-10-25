package org.caps.columbo.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话 token 用量（累计值）：按会话聚合的模型调用成本记账。
 * cachedTokens 是网关提示缓存命中部分（已计入 inputTokens），
 * 真实计费输入 = inputTokens - cachedTokens，拆开记才能算清"一次排查多少钱"。
 *
 * @author caps
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageVO {

    /** 累计输入 token（含缓存命中部分） */
    private long inputTokens;

    /** 累计输出 token */
    private long outputTokens;

    /** 其中提示缓存命中的 token（计入 inputTokens） */
    private long cachedTokens;

    /** 累计总 token */
    private long totalTokens;

    /** 模型调用次数（一次工具循环的每轮模型调用各计 1） */
    private long llmCalls;

    public boolean isEmpty() {
        return inputTokens <= 0 && outputTokens <= 0 && totalTokens <= 0 && llmCalls <= 0;
    }
}
