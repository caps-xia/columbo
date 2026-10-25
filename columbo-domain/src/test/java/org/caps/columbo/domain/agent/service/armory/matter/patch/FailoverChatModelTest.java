package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型故障切换（提炼自 waliapi Dispatcher）：可重试错误依次切备用、4xx 立即上抛、
 * 流式仅错误发生在任何数据吐出前才切换
 */
class FailoverChatModelTest {

    /** 可编程假模型：call/stream 可抛异常或返回值 */
    static class StubModel implements ChatModel, StreamingChatModel {
        final ChatResponse callResponse;
        final RuntimeException callError;
        final Flux<ChatResponse> streamFlux;
        final AtomicInteger calls = new AtomicInteger();
        final org.springframework.ai.chat.prompt.ChatOptions options;

        StubModel(ChatResponse callResponse, RuntimeException callError, Flux<ChatResponse> streamFlux,
                  org.springframework.ai.chat.prompt.ChatOptions options) {
            this.callResponse = callResponse;
            this.callError = callError;
            this.streamFlux = streamFlux;
            this.options = options;
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
            return options;
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return options;
        }

        StubModel(ChatResponse callResponse, RuntimeException callError, Flux<ChatResponse> streamFlux) {
            this(callResponse, callError, streamFlux, null);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (callError != null) {
                throw callError;
            }
            return callResponse;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            return streamFlux;
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                org.springframework.ai.chat.messages.AssistantMessage.builder().content(text).build())));
    }

    private static RuntimeException gatewayError() {
        return new RuntimeException("500 Internal Server Error");
    }

    private static RuntimeException badRequest() {
        return new RuntimeException("400 Bad Request: invalid model");
    }

    @Test
    void callFailsOverToBackupOnGatewayError() {
        StubModel primary = new StubModel(null, gatewayError(), null);
        StubModel backup = new StubModel(response("备用模型回答"), null, null);
        FailoverChatModel model = new FailoverChatModel(List.of(primary, backup));

        assertEquals("备用模型回答", model.call(new Prompt("hi")).getResult().getOutput().getText());
        assertEquals(1, backup.calls.get());
    }

    @Test
    void callNonRetryablePropagatesImmediately() {
        StubModel primary = new StubModel(null, badRequest(), null);
        StubModel backup = new StubModel(response("不应到达"), null, null);
        FailoverChatModel model = new FailoverChatModel(List.of(primary, backup));

        assertThrows(RuntimeException.class, () -> model.call(new Prompt("hi")));
        assertEquals(0, backup.calls.get());
    }

    @Test
    void streamFailsOverBeforeFirstEmission() {
        StubModel primary = new StubModel(null, null, Flux.error(gatewayError()));
        StubModel backup = new StubModel(null, null, Flux.just(response("备用流")));
        FailoverChatModel model = new FailoverChatModel(List.of(primary, backup));

        assertEquals("备用流", model.stream(new Prompt("hi")).blockLast()
                .getResult().getOutput().getText());
        assertEquals(1, backup.calls.get());
    }

    @Test
    void streamMidStreamErrorPropagates() {
        // 错误发生在数据吐出之后：无法干净重放，错误照常上抛
        StubModel primary = new StubModel(null, null, Flux.concat(
                Flux.just(response("已有部分输出")), Flux.error(gatewayError())));
        StubModel backup = new StubModel(null, null, Flux.just(response("不应到达")));
        FailoverChatModel model = new FailoverChatModel(List.of(primary, backup));

        assertThrows(RuntimeException.class, () -> model.stream(new Prompt("hi")).blockLast());
        assertEquals(0, backup.calls.get());
    }

    @Test
    void optionsDelegateToPrimaryModel() {
        // 回归（2026-09-28 线上）：FailoverChatModel 未实现 getOptions() → converter 现造
        // DefaultToolCallingChatOptions → OpenAiChatModel.createRequest 强转 ClassCastException
        var openAiOptions = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("glm-5.3-flash").build();
        StubModel primary = new StubModel(response("ok"), null, null, openAiOptions);
        StubModel backup = new StubModel(response("backup"), null, null, null);
        FailoverChatModel model = new FailoverChatModel(List.of(primary, backup));

        assertEquals(openAiOptions, model.getOptions(), "getOptions 必须委托主模型");
        assertEquals(openAiOptions, model.getDefaultOptions());
        // 全链路：带该 options 的 Prompt 经 failover 调用不再炸强转
        assertEquals("ok", model.call(new Prompt("hi", openAiOptions)).getResult().getOutput().getText());
    }

    @Test
    void allDelegatesFailingThrowsLastError() {
        StubModel a = new StubModel(null, gatewayError(), null);
        StubModel b = new StubModel(null, gatewayError(), null);
        FailoverChatModel model = new FailoverChatModel(List.of(a, b));

        RuntimeException e = assertThrows(RuntimeException.class, () -> model.call(new Prompt("hi")));
        assertTrue(e.getMessage().contains("500"));
    }
}
