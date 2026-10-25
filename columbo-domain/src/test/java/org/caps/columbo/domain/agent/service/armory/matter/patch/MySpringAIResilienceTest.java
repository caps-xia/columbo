package org.caps.columbo.domain.agent.service.armory.matter.patch;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import org.caps.columbo.domain.agent.service.session.RecordingSessionStore;
import org.caps.columbo.domain.agent.service.session.SessionTokenMeter;
import org.caps.columbo.domain.agent.service.session.StreamingMailbox;
import org.caps.columbo.domain.agent.service.armory.matter.tools.SpringAiToolCallbackTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：流式链路韧性——流中断安全重试（仅本轮零产出才重试）、停滞超时 + 非流式兜底、
 * 会话 token 计量（含缓存命中拆分）。
 */
class MySpringAIResilienceTest {

    @Test
    void streamErrorBeforeFirstChunkRetriedSafely() {
        // 首轮流未产出任何内容即报错（网关断流/5xx）：安全重试一轮后正常收尾，不整流报错
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.error(new RuntimeException("gateway reset")),
                Flux.just(textResponse("重试后正常结论。"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        List<Throwable> errors = new ArrayList<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set, errors::add);

        assertTrue(errors.isEmpty(), "零产出中断重试后不应再报错");
        assertEquals(2, model.capturedInstructions.size(), "应重试一轮（共 2 次模型调用）");
        String finalText = finalRef.get().content().orElseThrow()
                .parts().orElseThrow().get(0).text().orElseThrow();
        assertEquals("重试后正常结论。", finalText);
    }

    @Test
    void midStreamErrorAfterEmittedThinkNotRetried() {
        // 思考增量已转发下游后流中断：重试会让前端看到重复思考流——不允许发起新的模型轮；
        // 已产出的思考/正文按阶段性结果收尾落会话（2026-10-05 起不再整流报错清场）
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.concat(Flux.just(reasoningResponse("想了一下")),
                        Flux.error(new RuntimeException("half-way reset")))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        List<Throwable> errors = new ArrayList<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set, errors::add);

        assertEquals(0, errors.size(), "已产出内容按阶段性结果收尾，不整流报错");
        assertEquals(1, model.capturedInstructions.size(), "不应发起新的模型轮");
        String finalText = finalRef.get().content().orElseThrow()
                .parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("流式连接中断"), "final 应携带中断系统提示");
        assertTrue(finalText.contains("想了一下"), "已生成的思考应保留在最终结果中");
    }

    @Test
    void stallTimeoutTriggersRetriesThenNonStreamFallback() throws Exception {
        // 网关接受 stream=true 却永不吐块：停滞超时中断 → 安全重试 2 次 → 耗尽后降级非流式兜底。
        // 阈值取 1 秒，全程约 3 轮超时 ≈ 3s
        NeverStreamModel model = new NeverStreamModel(
                chatResponseWithUsage(textResponse("非流式兜底结论。"), usage(120, 30, 0)));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager(), null, null, 1);
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        List<Throwable> errors = new ArrayList<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set, errors::add, () -> {
                });

        assertTrue(errors.isEmpty());
        assertEquals(3, model.streamCalls, "首发 + 2 次安全重试");
        assertEquals(1, model.callCount, "重试耗尽后应降级非流式兜底");
        String finalText = finalRef.get().content().orElseThrow()
                .parts().orElseThrow().get(0).text().orElseThrow();
        assertEquals("非流式兜底结论。", finalText);
    }

    @Test
    void usageMeteredPerRoundWithCacheSplit() {
        // 每轮模型调用的 usage 按会话归属落账；缓存命中拆分（cached 计入 input）保留
        RecordingSessionStore store = new RecordingSessionStore();
        StreamingMailbox mailbox = new StreamingMailbox(store);
        SessionTokenMeter meter = new SessionTokenMeter(store);
        mailbox.beginRun("app", "u1", "s1");

        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(chatResponseWithUsage(
                        textResponse("先看日志："), usage(100, 50, 60)),
                        toolCallResponse("searchCode", "{\"k\":\"金额\"}")),
                Flux.just(chatResponseWithUsage(
                        textResponse("结论：金额校验不一致。"), usage(10, 5, 0)))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager(), mailbox, meter, 240);
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{\"matches\":[]}"), true)
                .blockingSubscribe(finalRef::set);

        System.out.println("PROBE recorded=" + store.recordedUsage + " rounds=" + model.capturedInstructions.size());
        assertEquals(2, store.recordedUsage.size(), "两轮模型调用各记一笔");
        TokenUsageVO first = store.recordedUsage.get(0);
        assertEquals(100, first.getInputTokens());
        assertEquals(50, first.getOutputTokens());
        assertEquals(60, first.getCachedTokens(), "缓存命中应单独拆出");
        assertEquals(150, first.getTotalTokens());
        assertEquals(1, first.getLlmCalls());
        TokenUsageVO second = store.recordedUsage.get(1);
        assertEquals(10, second.getInputTokens());
        assertEquals(0, second.getCachedTokens());
        assertEquals(2, store.recordedUsage.stream().mapToLong(TokenUsageVO::getLlmCalls).sum());
    }

    @Test
    void tokenUsageOfExtractsAndSplitsCache() {
        ChatResponse response = chatResponseWithUsage(
                textResponse("x"), usage(100, 50, 60));
        TokenUsageVO vo = MySpringAI.tokenUsageOf(response);
        assertEquals(100, vo.getInputTokens());
        assertEquals(50, vo.getOutputTokens());
        assertEquals(60, vo.getCachedTokens());
        assertEquals(150, vo.getTotalTokens());
        assertEquals(1, vo.getLlmCalls());
        // 无 usage / 全 0：返回 null 跳过记账
        assertNull(MySpringAI.tokenUsageOf(textResponse("x")));
        assertNull(MySpringAI.tokenUsageOf(chatResponseWithUsage(
                textResponse("x"), usage(0, 0, 0))));
    }

    // ------------------------------------------------------------------
    // fakes
    // ------------------------------------------------------------------

    /** 流式永远不吐块（模拟网关挂起），非流式路径正常返回固定响应 */
    static class NeverStreamModel implements ChatModel, StreamingChatModel {

        final ChatResponse fallbackResponse;
        int streamCalls;
        int callCount;

        NeverStreamModel(ChatResponse fallbackResponse) {
            this.fallbackResponse = fallbackResponse;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls++;
            return Flux.never();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            callCount++;
            return fallbackResponse;
        }
    }

    /** 按脚本依次返回响应流的假模型（与 MySpringAIToolLoopTest 同款，此处复制以保持测试独立） */
    static class ScriptedModel implements ChatModel, StreamingChatModel {

        final Deque<Flux<ChatResponse>> scripts;
        final List<List<Message>> capturedInstructions = new ArrayList<>();

        ScriptedModel(List<Flux<ChatResponse>> scripts) {
            this.scripts = new ArrayDeque<>(scripts);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            capturedInstructions.add(prompt.getInstructions());
            return scripts.pop();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("本测试不覆盖非流式重调");
        }
    }

    // ------------------------------------------------------------------
    // response builders
    // ------------------------------------------------------------------

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    private static ChatResponse toolCallResponse(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse reasoningResponse(String reasoning) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", reasoning))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse chatResponseWithUsage(ChatResponse base, Usage usage) {
        return new ChatResponse(base.getResults(),
                ChatResponseMetadata.builder().usage(usage).build());
    }

    private static Usage usage(long prompt, long completion, long cacheRead) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) prompt;
            }

            @Override
            public Integer getCompletionTokens() {
                return (int) completion;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }

            @Override
            public Long getCacheReadInputTokens() {
                return cacheRead;
            }
        };
    }

    private static LlmRequest request(String toolName, Function<String, String> toolFn) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(toolName)
                .description("desc of " + toolName)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return toolFn.apply(toolInput);
            }
        };
        return LlmRequest.builder()
                .contents(List.of(Content.builder().role("user")
                        .parts(List.of(Part.fromText("南京 hospital-tongxing-all 下单失败")))
                        .build()))
                .tools(Map.of(toolName, new SpringAiToolCallbackTool(callback)))
                .build();
    }
}
