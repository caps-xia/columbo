package org.caps.columbo.domain.agent.model.valobj;

import org.apache.commons.lang3.StringUtils;

/**
 * LLM 网关连接端点（Spring AI 2.0 起 OpenAiApi 被移除，连接参数收敛进 OpenAiChatOptions）。
 * 官方 openai-java SDK 的请求路径固定为 {@code {baseUrl}/chat/completions}，
 * 因此网关自定义的 completions-path 前缀（如 v1/）需并入 baseUrl。
 *
 * @param baseUrl 已并入路径前缀的网关地址
 * @param apiKey  网关密钥
 * @author caps
 */
public record AiApiEndpointVO(String baseUrl, String apiKey) {

    public static AiApiEndpointVO of(String baseUrl, String apiKey, String completionsPath) {
        String path = StringUtils.isNotBlank(completionsPath) ? completionsPath : "v1/chat/completions";
        String resolved = StringUtils.stripEnd(baseUrl, "/");
        if (path.endsWith("chat/completions")) {
            String prefix = StringUtils.strip(path.substring(0, path.length() - "chat/completions".length()), "/");
            if (StringUtils.isNotBlank(prefix) && !resolved.endsWith("/" + prefix)) {
                resolved = resolved + "/" + prefix;
            }
        }
        return new AiApiEndpointVO(resolved, apiKey);
    }
}
