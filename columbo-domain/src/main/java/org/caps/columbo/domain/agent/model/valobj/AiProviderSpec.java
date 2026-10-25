package org.caps.columbo.domain.agent.model.valobj;

import lombok.Data;

/**
 * 外部模型供应商定义（yml 多供应商注册表，ai.agent.providers.<key>）。
 * 与 agent 内置 ai-api 网关（AiApi）并列：内置网关是兜底供应商，
 * 注册表里的供应商通过「供应商key/模型名」限定写法被模型池引用。
 *
 * @author caps
 */
@Data
public class AiProviderSpec {

    /** API 协议：openai（OpenAI 兼容，默认）| anthropic（Anthropic Messages） */
    private AiApiFormat apiFormat = AiApiFormat.OPENAI;

    /** 网关地址，如 https://open.bigmodel.cn/api/paas/v4 */
    private String baseUrl;

    /** 供应商密钥（支持环境变量占位注入） */
    private String apiKey;

    /** 可选：OpenAI 兼容网关的 completions-path 前缀（如 v1），anthropic 协议忽略 */
    private String completionsPath;

    public enum AiApiFormat {
        OPENAI,
        ANTHROPIC
    }
}
