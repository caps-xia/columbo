package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同轮多工具并行执行回归：并发调度省墙钟（求和变取最大），
 * 且会话历史形态与串行完全一致（一条 assistant 全量 toolCalls + 一条聚合 ToolResponse，
 * 响应顺序=调用顺序）——模型视角零变化，准确度零影响。
 */
class ParallelToolCallingManagerTest {

    @Test
    void twoCallsExecuteConcurrentlyAndMergeInOrder() {
        AtomicInteger concurrentPeak = new AtomicInteger(0);
        AtomicInteger running = new AtomicInteger(0);
        ToolCallback slowA = callback("queryA", args -> {
            running.incrementAndGet();
            concurrentPeak.accumulateAndGet(running.get(), Math::max);
            sleep(400);
            running.decrementAndGet();
            return "{\"from\":\"A\"}";
        });
        ToolCallback slowB = callback("queryB", args -> {
            running.incrementAndGet();
            concurrentPeak.accumulateAndGet(running.get(), Math::max);
            sleep(400);
            running.decrementAndGet();
            return "{\"from\":\"B\"}";
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        long start = System.currentTimeMillis();
        ToolExecutionResult result = manager.executeToolCalls(
                prompt(slowA, slowB), twoCallResponse("queryA", "queryB"));
        long elapsed = System.currentTimeMillis() - start;

        // 并发生效：两调用有重叠（峰值≥2），墙钟 < 串行下界（800ms 留余量）
        assertTrue(concurrentPeak.get() >= 2, "两调用应并发执行，峰值=" + concurrentPeak.get());
        assertTrue(elapsed < 700, "墙钟应接近最慢调用而非求和: " + elapsed + "ms");

        // 历史形态与串行一致：prompt + 一条 assistant（两个 toolCall）+ 一条聚合 ToolResponse（顺序一致）
        List<Message> history = result.conversationHistory();
        assertEquals(3, history.size());
        AssistantMessage assistant = (AssistantMessage) history.get(1);
        assertEquals(2, assistant.getToolCalls().size());
        assertEquals("", assistant.getText(), "content null 应回补为空串（oneai 422 补丁）");
        ToolResponseMessage toolResp = (ToolResponseMessage) history.get(2);
        assertEquals(2, toolResp.getResponses().size());
        assertEquals("queryA", toolResp.getResponses().get(0).name());
        assertEquals("queryB", toolResp.getResponses().get(1).name());
        assertTrue(toolResp.getResponses().get(0).responseData().contains("A"));
        assertTrue(toolResp.getResponses().get(1).responseData().contains("B"));
    }

    @Test
    void sanitizeStaysActiveInParallelPath() {
        List<String> received = new java.util.ArrayList<>();
        ToolCallback a = callback("toolA", args -> {
            received.add(args);
            return "{}";
        });
        ToolCallback b = callback("toolB", args -> {
            received.add(args);
            return "{}";
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        // oneai 网关瑕疵：参数 JSON 前拼空对象——并行路径下同样要被剥掉
        ChatResponse dirty = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(null)
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "toolA", "{}{\"q\":\"一\"}"),
                        new AssistantMessage.ToolCall("c2", "function", "toolB", "{}{\"q\":\"二\"}")))
                .build())));
        manager.executeToolCalls(prompt(a, b), dirty);

        assertEquals(List.of("{\"q\":\"一\"}", "{\"q\":\"二\"}")
                .stream().sorted().toList(), received.stream().sorted().toList());
    }

    @Test
    void singleFailingCallSelfHealsIndependently() {
        ToolCallback ok = callback("okTool", args -> "{\"ok\":true}");
        ToolCallback bad = callback("badTool", args -> {
            throw new IllegalStateException("连接超时");
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "okTool", "{}"),
                        new AssistantMessage.ToolCall("c2", "function", "badTool", "{}")))
                .build())));
        ToolExecutionResult result = manager.executeToolCalls(prompt(ok, bad), response);

        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(2);
        assertEquals(2, toolResp.getResponses().size());
        // 失败者转错误文本回传（模型自愈），成功者不受拖累
        assertTrue(toolResp.getResponses().get(1).responseData().contains("工具未执行"));
        assertTrue(toolResp.getResponses().get(1).responseData().contains("连接超时"));
        assertTrue(toolResp.getResponses().get(0).responseData().contains("true"));
    }

    @Test
    void selfHealResponsePreservesToolCallId() {
        // 回归：自愈响应 id=null 会让 OpenAiChatModel 构建请求时 NPE（Kotlin 非参 toolCallId），
        // 整流死亡——自愈响应必须回传原 toolCall id
        ToolCallback ok = callback("okTool", args -> "{\"ok\":true}");
        ToolCallback bad = callback("badTool", args -> {
            throw new IllegalStateException("爆了");
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("call-orig-1", "function", "okTool", "{}"),
                        new AssistantMessage.ToolCall("call-orig-2", "function", "badTool", "{}")))
                .build())));
        ToolExecutionResult result = manager.executeToolCalls(prompt(ok, bad), response);

        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(2);
        assertEquals("call-orig-1", toolResp.getResponses().get(0).id());
        assertEquals("call-orig-2", toolResp.getResponses().get(1).id(), "自愈响应 id 必须回传原 toolCall id");
    }

    @Test
    void toolCallLimitExceededReturnsErrorResponseInsteadOfThrowing() {
        // Spring AI 每工具上限走 RETURN_ERROR_RESPONSE：超限不抛异常（抛了会被自愈路径接，
        // 框架构造的错误响应 id 完整，模型看到"上限"自己换策略）
        AtomicInteger calls = new AtomicInteger(0);
        ToolCallback chatty = callback("chattyTool", args -> {
            calls.incrementAndGet();
            return "{\"v\":1}";
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 3);

        ToolExecutionResult result = null;
        for (int round = 0; round < 5; round++) {
            ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "c" + round, "function", "chattyTool", "{}")))
                    .build())));
            // 上限计数按会话历史里已出现的工具响应累计，逐轮带上轮结果
            Prompt nextPrompt = result == null
                    ? prompt(chatty)
                    : new Prompt(new java.util.ArrayList<>(result.conversationHistory()),
                            ToolCallingChatOptions.builder().toolCallbacks(List.of(chatty)).build());
            result = manager.executeToolCalls(nextPrompt, response);
        }
        ToolResponseMessage lastResp = (ToolResponseMessage) result.conversationHistory().get(
                result.conversationHistory().size() - 1);
        assertTrue(calls.get() <= 3, "超过上限后不应再真实执行: " + calls.get());
        assertTrue(lastResp.getResponses().get(0).responseData().contains("limit")
                        || lastResp.getResponses().get(0).responseData().contains("上限")
                        || lastResp.getResponses().get(0).responseData().contains("exceed"),
                "超限轮应收到错误响应而非异常: " + lastResp.getResponses().get(0).responseData());
    }

    @Test
    void singleCallTakesSerialPath() {
        ToolCallback only = callback("onlyTool", args -> "{\"v\":1}");
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        ChatResponse response = new ChatResponse(List.of(new Generation(toolCallMessage(
                new AssistantMessage.ToolCall("c1", "function", "onlyTool", "{\"x\":1}")))));
        ToolExecutionResult result = manager.executeToolCalls(prompt(only), response);

        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(2);
        assertEquals(1, toolResp.getResponses().size());
        assertTrue(toolResp.getResponses().get(0).responseData().contains("1"));
    }

    @Test
    void inBatchDuplicateCallsExecuteOnceAndKeepIds() {
        // 2026-10-05 线上：同一并行批次里出现两条完全相同的调用（同毫秒双查日志）——
        // 重复项只执行一次，响应按各自 toolCall id 复制回填，id 不串
        AtomicInteger executions = new AtomicInteger(0);
        ToolCallback dup = callback("dupTool", args -> {
            executions.incrementAndGet();
            return "{\"v\":1}";
        });
        ToolCallback other = callback("otherTool", args -> "{\"other\":true}");
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        ChatResponse response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "dupTool", "{\"q\":\"x\"}"),
                        new AssistantMessage.ToolCall("c2", "function", "dupTool", "{\"q\":\"x\"}"),
                        new AssistantMessage.ToolCall("c3", "function", "otherTool", "{}")))
                .build())));
        ToolExecutionResult result = manager.executeToolCalls(prompt(dup, other), response);

        assertEquals(1, executions.get(), "完全相同的批内调用应只执行一次");
        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(2);
        assertEquals(3, toolResp.getResponses().size(), "响应数仍须与调用数一一对应");
        assertEquals("c1", toolResp.getResponses().get(0).id());
        assertEquals("c2", toolResp.getResponses().get(1).id(), "重复调用的响应 id 必须是各自的 toolCall id");
        assertEquals("c3", toolResp.getResponses().get(2).id());
        assertEquals(toolResp.getResponses().get(0).responseData(), toolResp.getResponses().get(1).responseData());
    }

    @Test
    void credentialFailureTellsModelNotToRetry() {
        // 2026-10-05 线上：token 过期类错误配"请检查参数后重试"文案，弱模型原样重发到熔断——
        // 凭证/环境类错误必须明确"重试无效"
        ToolCallback bad = callback("dbTool", args -> {
            throw new IllegalStateException("线上查询失败 HTTP 200 code=1001 重新登录（access_token 无效或已过期）");
        });
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        // 2 调用批次走并行路径：dbTool 失败（凭证类）+ okTool 成功
        ToolCallback ok = callback("okTool", args -> "{\"ok\":true}");
        ChatResponse batch = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", "dbTool", "{}"),
                        new AssistantMessage.ToolCall("c2", "function", "okTool", "{}")))
                .build())));
        ToolExecutionResult result = manager.executeToolCalls(prompt(bad, ok), batch);

        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(2);
        String failureText = toolResp.getResponses().get(0).responseData();
        assertTrue(failureText.contains("请勿再次调用"), "凭证类错误应阻止重试: " + failureText);
        assertTrue(!failureText.contains("请检查调用参数后重试"), "凭证类错误不应建议重试: " + failureText);
    }

    @Test
    void parallelResultsNeverResurfaceStaleHistoryResponses() {
        // 2026-10-05 线上串扰根因回归：多轮会话历史里已有旧工具响应时，并行批次必须回填
        // 「本次刚产生」的响应——旧实现取"历史第一条工具响应"（排查首轮 searchKnowledge 的
        // 结果），导致首个并行批次之后所有调用都收到第一轮旧结果（模型视角"返回串了"）。
        // 既有测试未暴露：测试 prompt 均为干净历史，无旧工具响应可串。
        ToolCallback a = callback("queryA", args -> "{\"from\":\"A-NEW\"}");
        ToolCallback b = callback("queryB", args -> "{\"from\":\"B-NEW\"}");
        ParallelToolCallingManager manager = new ParallelToolCallingManager(4, 200);

        // 会话历史已有一轮工具调用（旧响应 STALE）——模拟多轮排查的第二轮并行批次
        List<Message> history = new java.util.ArrayList<>(List.of(
                new UserMessage("排查问题"),
                toolCallMessage(new AssistantMessage.ToolCall("old-1", "function", "queryA", "{\"q\":\"old\"}")),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("old-1", "queryA", "{\"from\":\"STALE\"}"))).build()));
        Prompt promptWithHistory = new Prompt(history,
                ToolCallingChatOptions.builder().toolCallbacks(List.of(a, b)).build());

        ToolExecutionResult result = manager.executeToolCalls(promptWithHistory,
                twoCallResponse("queryA", "queryB"));

        ToolResponseMessage toolResp = (ToolResponseMessage) result.conversationHistory().get(
                result.conversationHistory().size() - 1);
        assertTrue(toolResp.getResponses().get(0).responseData().contains("A-NEW"),
                "queryA 应收到本次结果而非历史旧响应: " + toolResp.getResponses().get(0).responseData());
        assertTrue(toolResp.getResponses().get(1).responseData().contains("B-NEW"),
                "queryB 应收到本次结果而非历史旧响应: " + toolResp.getResponses().get(1).responseData());
    }

    private static Prompt prompt(ToolCallback... callbacks) {
        return new Prompt(List.of(new UserMessage("南京 下单失败")),
                ToolCallingChatOptions.builder().toolCallbacks(List.of(callbacks)).build());
    }

    private static ChatResponse twoCallResponse(String first, String second) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content(null)
                .toolCalls(List.of(
                        new AssistantMessage.ToolCall("c1", "function", first, "{}"),
                        new AssistantMessage.ToolCall("c2", "function", second, "{}")))
                .build())));
    }

    private static AssistantMessage toolCallMessage(AssistantMessage.ToolCall call) {
        return AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
    }

    private static ToolCallback callback(String name, java.util.function.Function<String, String> fn) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("desc of " + name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return fn.apply(toolInput);
            }
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
