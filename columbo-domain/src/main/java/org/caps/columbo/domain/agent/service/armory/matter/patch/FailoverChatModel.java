package org.caps.columbo.domain.agent.service.armory.matter.patch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 模型故障切换（提炼自 waliapi Dispatcher 的降级思路，裁剪为有序 failover）：
 * delegates[0] 为主模型，其余为 models 池内的备用模型（按配置顺序）。
 * <ul>
 *   <li>可重试错误：网关 5xx/429/超时/连接类 → 依次切换备用模型；</li>
 *   <li>不可重试错误（4xx 参数/鉴权类）→ 立即上抛，不掩盖真实问题；</li>
 *   <li>流式：仅当错误发生在**任何数据吐出之前**才切换（已吐出的增量无法撤回，中途错误照常上抛，
 *       由 MySpringAI 的空响应/截断守卫收尾）。</li>
 * </ul>
 */
@Slf4j
public class FailoverChatModel implements ChatModel, StreamingChatModel {

    private final List<ChatModel> delegates;

    public FailoverChatModel(List<ChatModel> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates 不能为空");
        }
        this.delegates = List.copyOf(delegates);
    }

    /**
     * options 必须委托主模型（通常为 OpenAiChatOptions）：MyMessageConverter 会把模型默认
     * options 合并进请求 Prompt；本类不覆写时接口默认返回 null → converter 现造
     * DefaultToolCallingChatOptions → OpenAiChatModel.createRequest 强转 ClassCastException
     * （2026-09-28 线上：非流式兜底路径首发即炸）。
     * 代价：备用模型与主模型必须同协议族（装配侧已按同格式过滤）。
     */
    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
        return delegates.get(0).getOptions();
    }

    @Override
    public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
        return delegates.get(0).getDefaultOptions();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Throwable last = null;
        for (int i = 0; i < delegates.size(); i++) {
            try {
                return delegates.get(i).call(prompt);
            } catch (Throwable e) {
                last = e;
                if (!isRetryable(e) || i + 1 >= delegates.size()) {
                    throw asRuntime(e);
                }
                log.warn("模型调用失败（{}），切换备用模型 {}/{}：{}",
                        rootMessage(e), i + 2, delegates.size(), delegates.get(i + 1).getClass().getSimpleName());
            }
        }
        throw asRuntime(last);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamAttempt(prompt, 0);
    }

    private Flux<ChatResponse> streamAttempt(Prompt prompt, int index) {
        if (index >= delegates.size()) {
            return Flux.error(new IllegalStateException("所有模型流式调用均失败"));
        }
        return Flux.defer(() -> {
            AtomicBoolean emitted = new AtomicBoolean(false);
            return delegates.get(index).stream(prompt)
                    .doOnNext(response -> emitted.set(true))
                    .onErrorResume(e -> {
                        if (emitted.get() || !isRetryable(e) || index + 1 >= delegates.size()) {
                            return Flux.error(e);
                        }
                        log.warn("流式调用失败（{}），切换备用模型 {}/{}", rootMessage(e), index + 2, delegates.size());
                        return streamAttempt(prompt, index + 1);
                    });
        });
    }

    /** 可重试判定：网关 5xx/429/超时/连接类错误；4xx 参数与鉴权类错误不重试 */
    static boolean isRetryable(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String message = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase();
            if (message.contains("timeout") || message.contains("timed out")
                    || message.contains("connection") || message.contains("connect")
                    || message.contains("502") || message.contains("503") || message.contains("504")
                    || message.contains("bad gateway") || message.contains("service unavailable")
                    || message.contains("internal server error") || message.contains("too many requests")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static RuntimeException asRuntime(Throwable e) {
        return e instanceof RuntimeException re ? re : new IllegalStateException(e);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
    }
}
