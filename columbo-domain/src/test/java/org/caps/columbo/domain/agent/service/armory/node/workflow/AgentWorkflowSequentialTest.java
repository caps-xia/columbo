package org.caps.columbo.domain.agent.service.armory.node.workflow;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.service.armory.matter.patch.MySpringAI;
import org.caps.columbo.domain.agent.service.armory.matter.patch.SanitizingToolCallingManager;
import org.caps.columbo.domain.agent.service.armory.matter.tools.SpringAiToolCallbackTool;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取证→成文 Sequential 工作流的行为回归（log-code-troubleshoot.yml 的运行时语义）。
 * 用脚本化假模型验证真实 ADK 1.9 Runner + InMemoryRunner 链路：
 * 1. 取证 agent 内部照常跑 MySpringAI 流式工具循环（工具轮不进会话）；
 * 2. 取证 agent 的档案作为最终事件落入会话，成文 agent 的下一轮模型请求
 *    经会话历史读到档案（阶段间传递机制），且只带自己的 system 指令（指令隔离）；
 * 3. 两个 agent 的最终输出按顺序流给订阅者（SSE 透传前提）。
 */
class AgentWorkflowSequentialTest {

    private static final String APP_NAME = "workflowTestApp";
    private static final String USER_ID = "test_user_wf";

    private static final String DOSSIER = "【环境与路由】南京/nj_master/Interface站/cls\n"
            + "【根因分析】库存扣减为0未拦截，下单失败。\n---";
    private static final String REPORT = "【结论速览】根因：库存扣减为0未拦截，先手工恢复库存。";

    @Test
    void evidenceThenReportStagesPassDossierViaSessionHistory() {
        ScriptedModel model = new ScriptedModel(List.of(
                // 取证 agent 第1轮：发起工具调用
                Flux.just(toolCallResponse("searchCode", "{\"query\":\"OrderController\",\"environment\":\"南京\"}")),
                // 取证 agent 第2轮：输出取证档案（以 --- 结尾）
                Flux.just(textResponse(DOSSIER)),
                // 成文 agent 第1轮：输出报告
                Flux.just(textResponse(REPORT))));

        List<String> toolInputs = new ArrayList<>();
        LlmAgent evidenceAgent = LlmAgent.builder()
                .name("logEvidenceAgent")
                .description("取证")
                .model(new MySpringAI(model, new SanitizingToolCallingManager()))
                .instruction("你是一名资深问题排查专家，产出取证档案。")
                .outputKey("troubleshoot_evidence")
                .tools(List.of(new SpringAiToolCallbackTool(callback("searchCode", input -> {
                    toolInputs.add(input);
                    return "{\"hits\":[\"OrderController.java:120\"]}";
                }))))
                .build();
        LlmAgent reportAgent = LlmAgent.builder()
                .name("logReportAgent")
                .description("成文")
                .model(new MySpringAI(model, new SanitizingToolCallingManager()))
                .instruction("你是排查报告撰写专家，基于取证档案成文。")
                .outputKey("troubleshoot_result")
                .build();
        SequentialAgent flow = SequentialAgent.builder()
                .name("logCodeTroubleshootFlow")
                .description("取证→成文")
                .subAgents(List.of(evidenceAgent, reportAgent))
                .build();

        InMemoryRunner runner = new InMemoryRunner(flow, APP_NAME);
        Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
        Content userMsg = Content.fromParts(Part.fromText("南京环境，下单失败，帮我排查"));

        RunConfig streaming = RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();
        List<String> finalTexts = new ArrayList<>();
        Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg, streaming);
        events.blockingForEach(event -> {
            if (!event.partial().orElse(false) && event.stringifyContent() != null
                    && !event.stringifyContent().isBlank()) {
                finalTexts.add(event.stringifyContent());
            }
        });

        // 1) 模型共被调 3 轮：取证工具轮 + 取证档案轮 + 成文轮
        assertEquals(3, model.capturedInstructions.size());
        // 工具确实在取证 agent 内部执行（参数原样）
        assertEquals(List.of("{\"query\":\"OrderController\",\"environment\":\"南京\"}"), toolInputs);
        // 第2轮请求带上工具结果（MySpringAI 内部循环，取证 agent 内闭环）
        assertTrue(model.capturedInstructions.get(1).stream()
                .anyMatch(ToolResponseMessage.class::isInstance));

        // 2) 成文轮：读到会话历史里的档案，且只带自己的 system 指令
        List<Message> reportRound = model.capturedInstructions.get(2);
        assertTrue(reportRound.stream().anyMatch(m -> m instanceof SystemMessage sys
                && sys.getText().contains("排查报告撰写专家")), "成文轮应带成文 agent 的 system 指令");
        assertTrue(reportRound.stream().noneMatch(m -> m instanceof SystemMessage sys
                && sys.getText().contains("资深问题排查专家")), "成文轮不应带取证 agent 的指令");
        assertTrue(reportRound.stream().anyMatch(m -> m.getText() != null
                        && m.getText().contains("库存扣减为0未拦截")),
                "成文轮应经会话历史读到取证档案");

        // 3) 两个阶段的最终输出按顺序流出：档案在前、报告在后
        assertEquals(2, finalTexts.size());
        assertTrue(finalTexts.get(0).contains("【根因分析】"));
        assertTrue(finalTexts.get(1).contains("【结论速览】"));

        // 4) 会话落库含两个阶段的产物（历史回放合并成一条消息的前提）
        Session reloaded = runner.sessionService()
                .getSession(APP_NAME, USER_ID, session.id(), java.util.Optional.empty()).blockingGet();
        String history = String.join("\n", reloaded.events().stream()
                .map(Event::stringifyContent).toList());
        assertTrue(history.contains("【根因分析】"));
        assertTrue(history.contains("【结论速览】"));
        assertFalse(history.contains("searchCode"), "工具轮不应进会话历史");
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

    /** 脚本化假模型：按调用顺序弹出预置流，记录每轮请求的 instructions（跨 agent 全局有序） */
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
            capturedInstructions.add(prompt.getInstructions());
            return scripts.pop().blockLast();
        }
    }
}
