package org.caps.columbo.domain.agent.adapter.port;

/**
 * 会话摘要端口：把一段排查对话记录压缩成可续接的摘要文本。
 * 实现在 infrastructure，走 OpenAI 兼容 chat completions（小模型即可）；
 * 摘要 SOP（保留什么、舍弃什么）属于领域知识，由 domain 侧构造提示词传入。
 *
 * @author caps
 */
public interface ISessionSummarizePort {

    /**
     * 生成摘要。
     *
     * @param systemPrompt 摘要规则（领域知识的 SOP 提示词）
     * @param userContent  待压缩的会话记录全文
     * @return 摘要正文；失败抛出异常，由调用方降级（普通翻篇）
     */
    String summarize(String systemPrompt, String userContent);
}
