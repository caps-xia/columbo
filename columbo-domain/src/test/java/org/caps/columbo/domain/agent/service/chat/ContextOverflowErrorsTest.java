package org.caps.columbo.domain.agent.service.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文超长错误识别测试：覆盖主流网关/模型方文案、异常链穿透、非超长错误不误判。
 */
class ContextOverflowErrorsTest {

    @Test
    void 主流超长文案可识别() {
        String[] messages = {
                "413 Payload Too Large",
                "HTTP 413: request entity too large",
                "This model's maximum context length is 131072 tokens",
                "This request exceeds the context_length_exceeded error code",
                "context length exceeded",
                "too many input tokens",
                "The input token limit has been exceeded",
                "Please reduce the length of the messages",
                "prompt is too long: 250000 tokens > 200000 maximum",
                "max_tokens and prompt length exceed context window",
                "Request too large for the model",
        };
        for (String message : messages) {
            assertTrue(ContextOverflowErrors.isContextOverflow(new RuntimeException(message)),
                    "应识别为超长: " + message);
        }
    }

    @Test
    void 异常链深层可穿透() {
        RuntimeException wrapped = new RuntimeException("模型调用失败",
                new RuntimeException("openai request failed: 413", new IllegalStateException("payload too large")));
        assertTrue(ContextOverflowErrors.isContextOverflow(wrapped));
    }

    @Test
    void 非超长错误不误判() {
        String[] messages = {
                "401 Unauthorized",
                "429 Too Many Requests",
                "Connection reset by peer",
                "Read timeout after 240s",
                "41343 tokens used",          // 413 嵌在数字里，词边界不命中
                "模型思考陷入重复循环",
                null,
        };
        for (String message : messages) {
            assertFalse(ContextOverflowErrors.isContextOverflow(new RuntimeException(message)),
                    "不应识别为超长: " + message);
        }
        assertFalse(ContextOverflowErrors.isContextOverflow(null));
        assertFalse(ContextOverflowErrors.isContextOverflow(
                new RuntimeException("outer", new RuntimeException("mid", new RuntimeException("inner")))));
    }

    @Test
    void 空消息不误判() {
        assertFalse(ContextOverflowErrors.isContextOverflow(new RuntimeException("  ")));
    }
}
