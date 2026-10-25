package org.caps.columbo.domain.agent.service.chat;

/**
 * 上下文超长（413 类）错误识别：对话历史撑爆模型上下文时，网关/模型以 400/413 状态
 * 或特定文案报错——此时唯一的自救是压缩/翻篇后重试，识别越准重试越安全。
 *
 * <p>按整条异常链逐层匹配消息文案（MySpringAI 会用 SpringAIErrorMapper 包装归一化消息，
 * 底层是 openai-java / Reactor / HTTP 客户端异常，形态不一），词表覆盖主流网关与模型方的
 * 惯用表述；词表外的新形态先按普通错误处理（用户收到报错后发"继续"，阈值翻篇兜底）。
 *
 * @author caps
 */
public final class ContextOverflowErrors {

    /** 消息词表（小写匹配） */
    private static final String[] MARKERS = {
            "payload too large",
            "request entity too large",
            "request too large",
            "maximum context length",
            "context_length_exceeded",
            "context length exceeded",
            "context window",
            "too many input tokens",
            "input token limit",
            "input tokens exceed",
            "token limit exceeded",
            "reduce the length",
            "prompt is too long",
            "prompt too long",
            "longer than the model's context length",
    };

    private ContextOverflowErrors() {
    }

    /**
     * 判断异常链上是否存在上下文超长错误。
     *
     * @param error 任意异常（可为 null）
     * @return true=应走"紧急压缩翻篇 + 重试"的自救路径
     */
    public static boolean isContextOverflow(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (matches(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean matches(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        // HTTP 413 状态码按词边界匹配（避免误伤 "1432" 之类数字）
        return java.util.regex.Pattern.compile("\\b413\\b").matcher(message).find();
    }
}
