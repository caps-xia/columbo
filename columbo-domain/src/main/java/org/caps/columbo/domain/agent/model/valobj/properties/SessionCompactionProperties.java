package org.caps.columbo.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 会话压缩配置：翻篇前把旧会话用小模型压缩成摘要结转到新上下文
 * （压缩优先、翻篇兜底）。摘要模型无需深度思考，用 flash 档即可。
 *
 * @author caps
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.session-compaction", ignoreInvalidFields = true)
public class SessionCompactionProperties {

    /** 是否启用压缩；关闭时翻篇维持旧行为（直接换空上下文） */
    private boolean enabled = false;

    /** OpenAI 兼容网关地址 */
    private String baseUrl;

    /** chat completions 路径 */
    private String completionsPath = "v1/chat/completions";

    /** API Key */
    private String apiKey;

    /** 摘要模型名（flash 档即可，无需深度思考） */
    private String model;

    /** 摘要请求超时（毫秒）：压缩发生在翻篇路径上，超时即降级普通翻篇 */
    private Integer timeoutMs = 60000;

    /** 送入摘要模型的会话记录字符上限（超出部分从头部截断丢弃） */
    private Integer maxTranscriptChars = 100000;

    /** 摘要正文字符上限（护栏，模型超长时截断） */
    private Integer maxSummaryChars = 2000;

    /** 事件数下限：少于此数不值得压缩（直接翻篇几乎无损失） */
    private Integer minEvents = 3;
}
