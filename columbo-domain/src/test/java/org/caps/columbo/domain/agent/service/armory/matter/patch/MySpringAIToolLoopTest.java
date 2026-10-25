package org.caps.columbo.domain.agent.service.armory.matter.patch;

import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.service.armory.matter.tools.SpringAiToolCallbackTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：Spring AI 2.0.1 起 ChatModel 不再内部执行工具，流式工具循环由 MySpringAI 重建
 * （升级前 2026-09-14 线上现象：模型返回纯 tool call → 工具被丢弃 → SSE 静默结束）。
 * 用脚本化假模型验证：流内 tool call → SanitizingToolCallingManager 执行（含 {} 前缀
 * 参数清洗）→ 带工具结果续流 → 最终事件只含正文。
 */
class MySpringAIToolLoopTest {

    @Test
    void executesToolThenRefollowsModelWithToolResult() {
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(toolCallResponse("searchKnowledge", "{\"keywords\":[\"食品\"]}")),
                Flux.just(textResponse("排查结论：列表渲染缺省值未兜底。"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        List<LlmResponse> out = new ArrayList<>();
        llm.generateContent(request("searchKnowledge", input -> {
            toolInputs.add(input);
            return "{\"results\":[\"历史案例A\"]}";
        }), true).blockingSubscribe(out::add);

        // 工具确实执行了，且参数原样
        assertEquals(List.of("{\"keywords\":[\"食品\"]}"), toolInputs);
        // 模型被调了两轮
        assertEquals(2, model.capturedInstructions.size());
        // 第二轮的请求里带上了 assistant 工具调用与工具结果
        List<Message> round2 = model.capturedInstructions.get(1);
        assertTrue(round2.stream().anyMatch(AssistantMessage.class::isInstance));
        ToolResponseMessage toolResult = round2.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .findFirst().orElseThrow();
        assertEquals(1, toolResult.getResponses().size());
        assertEquals("searchKnowledge", toolResult.getResponses().get(0).name());
        // 最终事件（非 partial）只含正文，不带 functionCall
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        assertEquals("排查结论：列表渲染缺省值未兜底。",
                last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow());
        assertTrue(last.content().orElseThrow().parts().orElseThrow().stream()
                .noneMatch(part -> part.functionCall().isPresent()));
    }

    @Test
    void sanitizesGatewayEmptyObjectPrefixInsideToolLoop() {
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                // oneai 网关瑕疵：参数 JSON 前拼了空对象
                Flux.just(toolCallResponse("searchKnowledge", "{}{\"keywords\":[\"斜杠\"]}")),
                Flux.just(textResponse("done"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(request("searchKnowledge", input -> {
            toolInputs.add(input);
            return "{}";
        }), true).blockingSubscribe(ignored -> {
        });

        assertEquals(List.of("{\"keywords\":[\"斜杠\"]}"), toolInputs);
    }

    @Test
    void plainTextStreamFinishesWithoutToolRound() {
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("直接回答，无需工具。"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(request("searchKnowledge", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        assertEquals(1, model.capturedInstructions.size());
        assertEquals("直接回答，无需工具。", finalRef.get().content().orElseThrow()
                .parts().orElseThrow().get(0).text().orElseThrow());
    }

    @Test
    void textStreamsAsPartialsBeforeFinalCarriesFull() {
        // 正文真流式：增量实时 partial 透传（前端不再干等整段蹦出），
        // final 事件仍带全量（落会话/历史回放），SSE 侧按已转发量去重
        List<LlmResponse> out = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("【结论速览】根因已定位。"), textResponse("修复建议见下。"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(request("searchKnowledge", input -> "{}"), true)
                .blockingSubscribe(out::add);

        List<LlmResponse> partials = out.stream().filter(r -> r.partial().orElse(false)).toList();
        assertTrue(partials.stream().anyMatch(r -> textOf(r).equals("【结论速览】根因已定位。")), "第一段应有 partial 增量");
        assertTrue(partials.stream().anyMatch(r -> textOf(r).equals("修复建议见下。")), "第二段应有 partial 增量");
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        assertEquals("【结论速览】根因已定位。修复建议见下。",
                last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow(),
                "final 仍带全量（落会话）");
    }

    private static String textOf(LlmResponse response) {
        return response.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
    }

    @Test
    void thinkDeltaOfAdaptsToCumulativeAndDeltaSemantics() {
        // 增量语义（SA 1.x）：逐块原样返回
        String[] last = {""};
        boolean[] cumulative = {false};
        assertEquals("用户", MySpringAI.thinkDeltaOf("用户", last, cumulative));
        assertEquals("报告", MySpringAI.thinkDeltaOf("报告", last, cumulative));
        assertFalse(cumulative[0]);

        // 累积语义（SA 2.0.1，2026-09-15 线上 30MB 事故根因）：第二块起以上一块原文为前缀，只取后缀
        String[] last2 = {""};
        boolean[] cumulative2 = {false};
        assertEquals("用户", MySpringAI.thinkDeltaOf("用户", last2, cumulative2));
        assertEquals("报告", MySpringAI.thinkDeltaOf("用户报告", last2, cumulative2));
        assertTrue(cumulative2[0]);
        assertEquals("了问题", MySpringAI.thinkDeltaOf("用户报告了问题", last2, cumulative2));
        // 无增长（纯文本窗口重复携带同一累积值）返回空串，由调用方跳过
        assertEquals("", MySpringAI.thinkDeltaOf("用户报告了问题", last2, cumulative2));
    }

    @Test
    void cumulativeReasoningStreamDoesNotDuplicateThink() {
        ScriptedModel model = new ScriptedModel(List.of(Flux.just(
                reasoningResponse("用户"),
                reasoningResponse("用户报告"),
                reasoningResponse("用户报告了一个问题"),
                textResponse("结论：已定位。"))));

        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchKnowledge", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        String finalText = finalRef.get().content().orElseThrow()
                .parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains(MySpringAI.THINK_OPEN + "用户报告了一个问题" + MySpringAI.THINK_CLOSE));
        // 前缀级联（思考 O(n²) 膨胀）不允许再出现
        assertFalse(finalText.contains("用户用户"));
        assertTrue(finalText.endsWith("结论：已定位。"));
    }

    @Test
    void stripsThinkBlocksFromHistoryBeforeSendingToModel() {
        ScriptedModel model = new ScriptedModel(List.of(Flux.just(textResponse("ok"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(LlmRequest.builder()
                .contents(List.of(
                        Content.builder().role("user").parts(List.of(Part.fromText("第一个问题"))).build(),
                        Content.builder().role("model").parts(List.of(Part.fromText(
                                MySpringAI.THINK_OPEN + "完整思考过程" + MySpringAI.THINK_CLOSE
                                        + MySpringAI.THINK_TIME_OPEN + "12" + MySpringAI.THINK_TIME_CLOSE
                                        + "答案正文"
                                        + "<thinking>模型回显的伪思考</thinking>尾巴"))).build()))
                .build(), true).blockingSubscribe(ignored -> {
                });

        AssistantMessage assistant = model.capturedInstructions.get(0).stream()
                .filter(AssistantMessage.class::isInstance)
                .map(AssistantMessage.class::cast)
                .findFirst().orElseThrow();
        // 历史思考块（含耗时标记与模型回显 <thinking>）剥离后再回传模型，正文保留
        assertEquals("答案正文尾巴", assistant.getText());
    }

    @Test
    void secondIdenticalCallInterceptedWithoutExecution() {
        // 连续第 2 次完全相同调用：确定性拦截不执行，拦截事实作为工具响应回传；
        // 模型第 3 轮改参数 → 自纠成功，不熔断
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(toolCallResponse("countClsLog", "{\"app\":\"3300400\",\"kw\":\"x\",\"timeWindow\":\"1d\"}")),
                Flux.just(toolCallResponse("countClsLog", "{\"app\":\"3300400\",\"kw\":\"x\",\"timeWindow\":\"1d\"}")),
                // 第 3 轮看到拦截响应后换了参数：不熔断
                Flux.just(toolCallResponse("countClsLog", "{\"app\":\"3300400\",\"kw\":\"x\",\"timeWindow\":\"2h\"}")),
                Flux.just(textResponse("按 2 小时窗口继续分析。"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        List<LlmResponse> out = new ArrayList<>();
        llm.generateContent(request("countClsLog", input -> {
            toolInputs.add(input);
            return "{\"count\":1747}";
        }), true).blockingSubscribe(out::add);

        // 第 2 次相同调用被拦截：工具只真实执行 2 次（第 1 次 + 改参后的第 3 次）
        assertEquals(2, toolInputs.size());
        assertEquals(4, model.capturedInstructions.size());
        // 第 3 轮的请求里应带拦截响应（含"完全相同"字样），而非真实工具结果——
        // 只看最后一条 ToolResponseMessage（配对 assistant2 的那条拦截响应）；
        // 更早的第 1 轮真实结果合法存在于历史中
        List<Message> round3 = model.capturedInstructions.get(2);
        ToolResponseMessage round3ToolResp = round3.stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(round3ToolResp.getResponses().get(0).responseData().contains("完全相同"), "第 3 轮应带拦截响应");
        assertTrue(!round3ToolResp.getResponses().get(0).responseData().contains("1747"), "拦截轮不应带真实结果");
        // 最终正常收尾，无熔断提示
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        assertFalse(last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow().contains("循环调用"));
    }

    @Test
    void interruptionAfterOutputClosesWithPartialResult() {
        // 硬中断（产出后断流）：不报错清场——已流出的正文保阶段性结果落库收尾，
        // 附"可发继续"提示，用户可从最近完整状态续查
        List<LlmResponse> out = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.concat(
                        Flux.just(textResponse("已定位到 OrderController:120 判空缺失。")),
                        Flux.error(new RuntimeException("Connection reset")))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(out::add, errors::add);

        assertTrue(errors.isEmpty(), "有产出的中断不应整流报错: " + errors);
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        String finalText = last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("OrderController:120"), "已产出正文应保留");
        assertTrue(finalText.contains("流式连接中断"), "应附中断提示");
        assertTrue(finalText.contains("阶段性结果"));
    }

    @Test
    void interruptionWithNoOutputStillErrors() {
        // 首轮即断且无工具轮：没有可保留的东西，最终仍整流报错——
        // 完整契约是：安全重试 2 次（脚本给足 3 个 error 流）→ 重试耗尽降级非流式兜底 →
        // 兜底也失败（ScriptedModel.call 不支持，抛异常）→ emitError
        List<LlmResponse> out = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.error(new RuntimeException("Connection reset")),
                Flux.error(new RuntimeException("Connection reset")),
                Flux.error(new RuntimeException("Connection reset"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(out::add, errors::add);

        assertTrue(out.isEmpty() || out.get(out.size() - 1).partial().orElse(false));
        assertEquals(1, errors.size(), "零产出中断应报错");
        assertEquals(3, model.capturedInstructions.size(), "安全重试共应请求 3 轮");
    }

    @Test
    void identicalToolCallsAbortedFastAsLoop() {
        // 模型连续发出完全相同的调用（典型：工具报错后原样重试）→ 第 3 轮熔断，
        // 不再整流报错：以阶段性结果+系统提示正常收尾
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(toolCallResponse("searchKnowledge", "{\"keywords\":[\"同参\"]}")),
                Flux.just(toolCallResponse("searchKnowledge", "{\"keywords\":[\"同参\"]}")),
                Flux.just(toolCallResponse("searchKnowledge", "{\"keywords\":[\"同参\"]}")),
                // 不应被消费：熔断发生在第 3 轮工具执行之前
                Flux.just(textResponse("不应到达这里"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        List<LlmResponse> out = new ArrayList<>();
        llm.generateContent(request("searchKnowledge", input -> {
            toolInputs.add(input);
            return "{\"error\":\"模拟工具持续报错\"}";
        }), true).blockingSubscribe(out::add);

        // 第 1 次真实执行；第 2 次相同调用在执行前被拦截（streak>=2 不再真实执行，
        // 拦截响应回传逼模型自纠）；第 3 次仍相同 → 熔断（工具共真实执行 1 次）
        assertEquals(1, toolInputs.size());
        assertEquals(3, model.capturedInstructions.size());
        // 正常收尾（非 onError），最终事件带系统提示
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        String finalText = last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("循环调用"));
        assertTrue(finalText.contains("阶段性结果"));
    }

    @Test
    void roundsCapKeepsPartialResultInsteadOfError() {
        // 长排查（每轮参数不同，如分段回扫日志）：不触发相同调用熔断，到总轮数上限时
        // 保留已产出正文按阶段性结果收尾（回归：2026-09-18 线上 20 轮上限把 13 分钟排查清空）
        List<String> toolInputs = new ArrayList<>();
        List<Flux<ChatResponse>> scripts = new ArrayList<>();
        for (int i = 1; i <= 41; i++) {
            scripts.add(Flux.just(
                    textResponse(i == 1 ? "已找到线索，继续核实。" : ""),
                    toolCallResponse("searchKnowledge", "{\"seg\":" + i + "}")));
        }
        ScriptedModel model = new ScriptedModel(scripts);
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        List<LlmResponse> out = new ArrayList<>();
        llm.generateContent(request("searchKnowledge", input -> {
            toolInputs.add(input);
            return "{}";
        }), true).blockingSubscribe(out::add);

        // 第 41 轮超上限熔断：前 40 轮工具都执行了，第 41 轮不执行
        assertEquals(40, toolInputs.size());
        assertEquals(41, model.capturedInstructions.size());
        LlmResponse last = out.get(out.size() - 1);
        assertFalse(last.partial().orElse(false));
        String finalText = last.content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        // 此前正文保留 + 中止提示附加，而不是整个流报错
        assertTrue(finalText.contains("已找到线索，继续核实。"));
        assertTrue(finalText.contains("上限 40"));
        assertTrue(finalText.contains("阶段性结果"));
    }

    @Test
    void cancellationStopsSubsequentToolRounds() throws Exception {
        // SSE 超时/客户端断连 → 外层 dispose 传播：取消后在途轮允许收尾，但不再执行工具、
        // 不再开新的模型轮（回归：2026-09-18 线上超时后后台又空烧 3 分多钟跑到自然结束）
        List<String> toolInputs = new ArrayList<>();
        List<Flux<ChatResponse>> scripts = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            // boundedElastic 异步发射：订阅即时返回，制造"轮间取消"窗口（同步 Flux 一趟跑完测不了）
            scripts.add(Flux.just(toolCallResponse("searchKnowledge", "{\"seg\":" + i + "}"))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
        }
        ScriptedModel model = new ScriptedModel(scripts);
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        java.util.concurrent.CountDownLatch firstTool = new java.util.concurrent.CountDownLatch(1);
        AtomicReference<io.reactivex.rxjava3.disposables.Disposable> sub = new AtomicReference<>();
        sub.set(llm.generateContent(request("searchKnowledge", input -> {
            toolInputs.add(input);
            firstTool.countDown();
            return "{}";
        }), true).subscribe());
        assertTrue(firstTool.await(5, java.util.concurrent.TimeUnit.SECONDS), "首轮工具应已执行");
        sub.get().dispose();
        // 允许在途轮收尾，随后模型轮数/工具执行都不应再增长
        Thread.sleep(300);
        int rounds = model.capturedInstructions.size();
        int executed = toolInputs.size();
        Thread.sleep(500);
        assertEquals(rounds, model.capturedInstructions.size(), "取消后不应再开新的模型轮");
        assertEquals(executed, toolInputs.size(), "取消后不应再执行工具");
        assertTrue(rounds <= 3, "取消应及时生效，实际模型轮数：" + rounds);
    }

    @Test
    void emptyModelRoundRetriedOnceThenRecovers() {
        // 回归（2026-09-21 线上）：工具执行后的模型轮返回空流，旧逻辑当正常结束，
        // 回答停在半句且无报错；现在原样重试一轮，重试成功则流继续到正常结论
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("已定位到金额校验代码，现在读源码分析："),
                        toolCallResponse("searchCode", "{\"keyword\":\"金额不匹配\"}")),
                // 工具后的模型轮：空流（无正文/思考/工具）
                Flux.empty(),
                // 重试轮：正常输出结论
                Flux.just(textResponse("分析完成：回调金额与订单总金额校验不一致导致回调被拒。"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> {
            toolInputs.add(input);
            return "{\"matches\":[]}";
        }), true).blockingSubscribe(finalRef::set);

        assertEquals(1, toolInputs.size());
        // 3 次模型调用：首发(正文+工具) / 空轮 / 空轮重试(结论)
        assertEquals(3, model.capturedInstructions.size());
        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("现在读源码分析："));
        assertTrue(finalText.endsWith("分析完成：回调金额与订单总金额校验不一致导致回调被拒。"));
        assertFalse(finalText.contains("【系统提示】"), "重试成功不应出现中断提示");
    }

    @Test
    void consecutiveEmptyRoundsEndWithInterruptionNotice() {
        // 连续两次空响应：不再静默收尾——保留已产出正文 + 系统提示，且观测侧记错误
        List<String> toolInputs = new ArrayList<>();
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("阶段性结论先行。"),
                        toolCallResponse("searchCode", "{\"keyword\":\"回调\"}")),
                Flux.empty(),
                Flux.empty()));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> {
            toolInputs.add(input);
            return "{}";
        }), true).blockingSubscribe(finalRef::set);

        assertEquals(3, model.capturedInstructions.size());
        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("阶段性结论先行。"), "中断前已产出正文必须保留");
        assertTrue(finalText.contains("异常中断"));
        assertTrue(finalText.contains("阶段性结果"));
    }

    @Test
    void reasoningOnlyRoundRetriedThenRecovers() {
        // 回归（2026-09-26 线上）：模型轮输出了思考却没给正文/工具调用就结束，旧逻辑当正常
        // 完成，回答停在半句话；现在原样重试一轮，重试成功则流继续到正常结论
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("先看过滤链："),
                        toolCallResponse("searchCode", "{\"keyword\":\"门店\"}")),
                // 工具后的模型轮：只有思考、无正文无工具
                Flux.just(reasoningResponse("需要确认城市过滤条件")),
                // 重试轮：正常输出结论
                Flux.just(textResponse("结论：门店被关键字过滤清空。"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        // 3 次模型调用：首发(正文+工具) / 仅思考轮 / 重试轮(结论)
        assertEquals(3, model.capturedInstructions.size());
        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("先看过滤链："));
        assertTrue(finalText.endsWith("结论：门店被关键字过滤清空。"));
        assertFalse(finalText.contains("【系统提示】"), "重试成功不应出现中断提示");
    }

    @Test
    void consecutiveReasoningOnlyRoundsEndWithNotice() {
        // 连续两轮仅思考无正文：保留已产出正文 + 异常中断提示
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("阶段性结论。"),
                        toolCallResponse("searchCode", "{\"keyword\":\"x\"}")),
                Flux.just(reasoningResponse("想了一半")),
                Flux.just(reasoningResponse("继续想"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("阶段性结论。"));
        assertTrue(finalText.contains("仅思考无正文"));
        assertTrue(finalText.contains("阶段性结果"));
    }

    @Test
    void finishReasonLengthEndsWithTruncationNotice() {
        // finishReason=length：输出被长度上限截断，保留已生成部分 + 明确提示（不静默断尾）
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(textResponse("分析到一半："),
                        new ChatResponse(List.of(new Generation(
                                AssistantMessage.builder().content("截断前最后一段。").build(),
                                org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                                        .finishReason("length").build()))))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("分析到一半："));
        assertTrue(finalText.contains("长度上限"));
        assertTrue(finalText.contains("继续"));
    }

    @Test
    void reasoningLoopRetriesSilentlyThenRecovers() {
        // 回归（2026-10-06 线上）：复读是模型随机故障——检测掐断后同一 prompt 静默重试一次，
        // 第二轮正常产出即恢复；旧思考段就地闭合，不附加"已中止"系统提示
        StringBuilder looped = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            looped.append("并行两个调用。");
        }
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(reasoningResponse(looped.toString())),
                Flux.just(textResponse("重试后正常结论。"))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        assertEquals(2, model.capturedInstructions.size(), "复读后应静默重试一轮");
        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("重试后正常结论。"), "重试轮的正文应正常透出");
        assertTrue(!finalText.contains("已中止"), "静默重试不应附加中止提示");
        assertTrue(finalText.contains("并行两个调用。"), "被掐断的旧思考应作为分段保留在最终内容中");
    }

    @Test
    void reasoningLoopRetryExhaustedAborts() {
        // 重试预算耗尽（连续两轮复读）：按原语义中止收尾，不再无限重试
        StringBuilder looped = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            looped.append("并行两个调用。");
        }
        ScriptedModel model = new ScriptedModel(List.of(
                Flux.just(reasoningResponse(looped.toString())),
                Flux.just(reasoningResponse(looped.toString()))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        assertEquals(2, model.capturedInstructions.size(), "仅重试一次，不无限重试");
        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("思考陷入重复循环已中止"));
        assertTrue(finalText.contains("继续"));
    }

    @Test
    void reasoningOverRoundCapAborted() {
        // 单轮思考超 30k 上限：非重复文本也中止（防无复读的超长空转）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1200; i++) {
            big.append("第").append(i).append("步推演，考虑字段 a").append(i).append(" 与分支 b").append(i).append(" 的组合。\n");
        }
        assertTrue(big.length() > 30_000);
        ScriptedModel model = new ScriptedModel(List.of(Flux.just(reasoningResponse(big.toString()))));
        MySpringAI llm = new MySpringAI(model, new SanitizingToolCallingManager());
        AtomicReference<LlmResponse> finalRef = new AtomicReference<>();
        llm.generateContent(request("searchCode", input -> "{}"), true)
                .blockingSubscribe(finalRef::set);

        String finalText = finalRef.get().content().orElseThrow().parts().orElseThrow().get(0).text().orElseThrow();
        assertTrue(finalText.contains("思考超长已止损"));
        assertTrue(finalText.contains("30000"));
    }

    @Test
    void reasoningLoopDetectorDiscriminates() {
        // 复读 → true
        StringBuilder looped = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            looped.append("并发两个调用。");
        }
        StringBuilder buf = new StringBuilder("前言。");
        buf.append(looped);
        assertTrue(MySpringAI.reasoningLoopDetected(buf, 0));
        // 正常长思考（无短周期重复）→ false
        StringBuilder normal = new StringBuilder("前言。");
        for (int i = 0; i < 40; i++) {
            normal.append("第").append(i).append("步：检查字段 a").append(i).append(" 的取值与分支条件 b").append(i).append("。\n");
        }
        assertFalse(MySpringAI.reasoningLoopDetected(normal, 0));
        // 起始段是重复的、尾部是正常推进 → false（只看最近窗口）
        assertFalse(MySpringAI.reasoningLoopDetected(new StringBuilder(looped).append(normal), 0));
        // 回归（2026-10-05 线上）：整句循环、句间夹 <br>/杂散符号——短周期原文匹配够不着，
        // 归一化后按 3 次连续重复判环
        String sentence = "那三条ERROR是另一门店的同类报错，与本案门店无关，档案中会分开标注，再抽查一条确认同属本案门店";
        StringBuilder longLoop = new StringBuilder("前言。");
        for (int i = 0; i < 4; i++) {
            longLoop.append(sentence).append(i % 2 == 0 ? "<br>" : "†");
        }
        assertTrue(MySpringAI.reasoningLoopDetected(longLoop, 0));
        // 相同句子只出现 2 次（引用复述而非循环）→ false
        StringBuilder twice = new StringBuilder("前言。");
        twice.append(sentence).append("。<br>").append(sentence).append("。<br>接下来核对另一段完全不同的证据链内容，避免遗漏。");
        assertFalse(MySpringAI.reasoningLoopDetected(twice, 0));
    }

    /** 按脚本依次返回响应流的假模型，同时记录每轮请求的 instructions */
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
            throw new UnsupportedOperationException("非流式路径不在本测试范围");
        }
    }

    private static ChatResponse toolCallResponse(String name, String arguments) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments)))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build())));
    }

    /** 模拟流式 chunk：reasoning_content 位于 AssistantMessage metadata（与 Spring AI 2.0 一致） */
    private static ChatResponse reasoningResponse(String reasoning) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", reasoning))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
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
                        .parts(List.of(Part.fromText("南京 hospital-tongxing-all 食品经营许可字段显示斜杠")))
                        .build()))
                .tools(Map.of(toolName, new SpringAiToolCallbackTool(callback)))
                .build();
    }

    @Test
    void fragmentationDetectorCatchesSynonymRotationBurst() {
        // 2026-10-20 线上真实形态：模型在"并行发起两个工具调用"决策点塌缩成同义词轮转——
        // 每圈换词、单元互不相同，精确周期检测打不中，但句子碎成 2~3 字符
        StringBuilder think = new StringBuilder();
        think.append("用户报的是南京环境商家账号导不进去产品，附图显示导入进度弹窗。先查知识库和日志探量，")
             .append("失败原因是任务级失败文案，重点找异步导入的处理链路和异常兜底。");
        String[] banks = {"双发", "开查", "就绪", "两步走", "同时进行", "拿数据", "定位常量", "读源码",
                "发请求", "全速", "两路并进", "双轨", "并行", "启动", "立发", "同步推进", "直接干", "拉数据",
                "读常量", "开工", "动手", "行动", "出击", "双请求", "一起打", "并发", "取证", "齐发", "即取",
                "快", "冲", "上", "去", "干", "行", "动", "好", "收到", "明白", "ok"};
        // 乱序轮转（固定种子）确保无任何精确周期——与线上真实样本同形态；约 1500 字符，远超 600 窗口
        java.util.Random rnd = new java.util.Random(20261020);
        for (int pass = 0; pass < 6; pass++) {
            List<String> turn = new ArrayList<>(List.of(banks));
            java.util.Collections.shuffle(turn, rnd);
            for (String b : turn) {
                think.append(b).append("。");
            }
        }
        // 精确周期检测确认漏过（回归前提）
        assertFalse(MySpringAI.reasoningLoopDetected(think, 0));
        // 碎片化检测兜住
        assertTrue(MySpringAI.reasoningFragmentationDetected(think, 0));
    }

    @Test
    void fragmentationDetectorIgnoresNormalBulletThinking() {
        // 正常要点式思考：句子明显长于判定线，句数再多也不该命中
        StringBuilder think = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            think.append("第").append(i).append("步先核对excel_task表failed_message字段与日志堆栈是否一致，")
                 .append("再对照DrugsNewSpuImportNjHandler的判空分支。");
        }
        assertFalse(MySpringAI.reasoningFragmentationDetected(think, 0));
    }

    @Test
    void fragmentationDetectorIgnoresDenseFieldEnumeration() {
        // 误报压力测试：DDL/字段枚举会让标点切分出大量短单元，但句均长仍在判定线之上
        StringBuilder think = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            think.append("excel_task字段核对：id,create_user_code,business_code,estimate_count,")
                 .append("success_count,failed_count,failed_message,start_time逐个与DDL比对; ");
        }
        assertFalse(MySpringAI.reasoningFragmentationDetected(think, 0));
    }

    @Test
    void fragmentationDetectorNeedsFullWindow() {
        // 窗口不足（<600 字符）时即使已经碎也先不判定，避免开局误伤
        StringBuilder think = new StringBuilder("先查库。再读码。两操作。并行。双发。开查。就绪。");
        assertFalse(MySpringAI.reasoningFragmentationDetected(think, 0));
    }
}
