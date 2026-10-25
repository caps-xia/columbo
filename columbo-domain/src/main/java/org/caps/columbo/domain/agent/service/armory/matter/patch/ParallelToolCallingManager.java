package org.caps.columbo.domain.agent.service.armory.matter.patch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 同轮多工具调用的并行执行管理器：模型并行打包的独立调用（如多环境日志探量、
 * 双站点追踪）并发执行，墙钟从"求和"变"取最大"（日志单查 4-16s 量级，三个
 * 独立查询串行要 12-48s，并发只要最慢那个）。
 *
 * <p>行为与 {@link SanitizingToolCallingManager} 严格对齐：参数 {} 前缀清洗、
 * assistant content null 补空串、会话历史形态不变（一条 assistant 携带全部
 * toolCalls + 一条聚合 ToolResponseMessage，响应顺序与调用顺序一致——并发只改
 * 执行调度，模型看到的历史与串行完全相同，准确度零影响）。
 *
 * <p>差异（有意为之）：单个调用执行失败只影响该调用（转错误文本回传模型自愈），
 * 不再拖累整轮——比旧的整轮自愈语义更细。单调用轮次与 parallelism&lt;=1 时走原
 * 串行路径，行为零变化。
 *
 * <p>治理：有界守护线程池（ai.agent.tool-parallelism，默认 4，0/1 关闭并行）；
 * 工具自身的超时由工具层负责（HTTP 客户端/命令超时），此处不外加 get 超时。
 */
@Slf4j
public class ParallelToolCallingManager implements ToolCallingManager {

    private final SanitizingToolCallingManager serial;
    private final ToolCallingManager delegate;
    private final ExecutorService executor;
    private final int parallelism;

    public ParallelToolCallingManager(int parallelism, int toolCallLimit) {
        this.parallelism = parallelism;
        this.serial = new SanitizingToolCallingManager(toolCallLimit);
        // Spring AI 2.0 默认每工具每轮 40 次上限（防死循环护栏）；本项目轮次治理由
        // MySpringAI 的 ToolLoopGuard 负责（40 轮/3 连相同快断），这里放宽到可配值，
        // 且超限走 RETURN_ERROR_RESPONSE（错误响应回模型自换策略）而非抛异常——
        // 抛异常会被自愈路径接住，错误响应则由框架构造、toolCallId 必然完整
        this.delegate = ToolCallingManager.builder()
                .maxCallsPerTool(toolCallLimit)
                .onLimitExceeded(org.springframework.ai.model.tool.ToolCallLimitBehavior.RETURN_ERROR_RESPONSE)
                .build();
        this.executor = parallelism > 1 ? sharedPool(parallelism) : null;
    }

    /**
     * 进程级共享守护线程池（按 parallelism 缓存）：armory 热切换/重建 agent 时每次
     * new 本类，实例级池会随重建持续累积线程（daemon 不阻断退出但仍属泄漏），
     * 共享一份与 parallelism 配置值对应的池即可——配置不变时全程复用。
     */
    private static final Map<Integer, ExecutorService> SHARED_POOLS = new ConcurrentHashMap<>();

    private static ExecutorService sharedPool(int parallelism) {
        return SHARED_POOLS.computeIfAbsent(parallelism, p -> Executors.newFixedThreadPool(p, runnable -> {
            Thread thread = new Thread(runnable, "tool-parallel-exec");
            thread.setDaemon(true);
            return thread;
        }));
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return serial.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        if (parallelism <= 1 || executor == null || countCalls(chatResponse) <= 1) {
            return serial.executeToolCalls(prompt, chatResponse);
        }
        ChatResponse sanitized = SanitizingToolCallingManager.sanitizeToolCallArguments(chatResponse);
        // 多 generation 携带工具调用属异常形态（ChunkMerger 正常只产一条）：不冒险，回退串行
        if (generationsWithCalls(sanitized) != 1) {
            return serial.executeToolCalls(prompt, chatResponse);
        }
        return executeConcurrently(prompt, sanitized);
    }

    private ToolExecutionResult executeConcurrently(Prompt prompt, ChatResponse sanitized) {
        AssistantMessage assistant = sanitized.getResults().get(0).getOutput();
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();

        // 批内去重：弱模型会在同一并行批次里发出完全相同的调用（2026-10-05 线上：
        // 同一毫秒两条相同的日志查询），重复项只执行一次，结果按各自 toolCall id 复制回填
        Map<String, Future<ToolExecutionResult>> executions = new LinkedHashMap<>(calls.size());
        int duplicatedCalls = 0;
        for (AssistantMessage.ToolCall call : calls) {
            String key = toolCallKey(call);
            if (executions.containsKey(key)) {
                // 先查再提交：putIfAbsent(key, submit(...)) 的 value 急切求值，重复项也会真实执行
                duplicatedCalls++;
                continue;
            }
            ChatResponse split = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content(assistant.getText())
                    .properties(assistant.getMetadata())
                    .toolCalls(List.of(call))
                    .media(assistant.getMedia())
                    .build())));
            // 调用用户上下文随任务过池：提交线程（MySpringAI 已绑定）捕获，worker 线程内恢复
            String callUser = org.caps.columbo.domain.agent.service.context.AgentCallContext.current();
            executions.put(key, executor.submit(() ->
                    org.caps.columbo.domain.agent.service.context.AgentCallContext.with(callUser, () ->
                            delegate.executeToolCalls(prompt, split))));
        }
        if (duplicatedCalls > 0) {
            log.info("并行批次内 {} 个重复调用已合并执行（批次共 {} 个调用）", duplicatedCalls, calls.size());
        }

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        boolean returnDirect = false;
        for (int i = 0; i < calls.size(); i++) {
            AssistantMessage.ToolCall call = calls.get(i);
            ToolResponseMessage.ToolResponse response;
            try {
                ToolExecutionResult result = executions.get(toolCallKey(call)).get();
                returnDirect |= result.returnDirect();
                // 重复调用共享同一执行结果，响应必须按本调用的 id 重建（id=null 会让后续模型请求全部 NPE）
                ToolResponseMessage.ToolResponse extracted = extractResponse(call, result);
                if (extracted == null) {
                    response = errorResponse(call, new IllegalStateException("工具执行结果缺失"));
                } else {
                    response = new ToolResponseMessage.ToolResponse(call.id(), call.name(), extracted.responseData());
                }
            } catch (Exception e) {
                // 拆分执行外的意外（中断等）：兜底成错误文本响应，不炸整轮
                log.warn("并行工具执行异常，转错误文本自愈: {}", e.getMessage());
                response = errorResponse(call, e);
            }
            responses.add(response);
        }

        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant.getText() == null
                ? AssistantMessage.builder()
                        .content("")
                        .properties(assistant.getMetadata())
                        .toolCalls(calls)
                        .media(assistant.getMedia())
                        .build()
                : assistant);
        history.add(ToolResponseMessage.builder().responses(responses).build());
        return ToolExecutionResult.builder()
                .conversationHistory(history)
                .returnDirect(returnDirect)
                .build();
    }

    /** 批内去重键：工具名+参数原文（与 ToolLoopGuard 的跨轮签名同口径） */
    private static String toolCallKey(AssistantMessage.ToolCall call) {
        return call.name() + "(" + call.arguments() + ")";
    }

    /**
     * 从单调用执行结果里取「本次刚产生」的工具响应：delegate 返回的历史 = 完整会话历史 +
     * 本次新追加的一条 ToolResponseMessage（在末尾），必须按 toolCall id 命中或从末尾定位。
     * 2026-10-05 线上串扰根因：旧实现取「历史里第一条工具响应」——那是本轮之前最早一轮
     * （排查首轮固定先调 searchKnowledge）的结果，导致首个并行批次之后的所有并行调用全部
     * 收到第一轮的旧结果（模型视角"三个调用都返回了 searchKnowledge 的结果"）。
     * 既有测试没暴露它：测试的 prompt 都是干净历史，没有旧工具响应可串。
     */
    private static ToolResponseMessage.ToolResponse extractResponse(AssistantMessage.ToolCall call,
                                                                    ToolExecutionResult result) {
        for (int i = result.conversationHistory().size() - 1; i >= 0; i--) {
            Message message = result.conversationHistory().get(i);
            if (!(message instanceof ToolResponseMessage toolResponse)) {
                continue;
            }
            // 最新一条聚合 ToolResponseMessage 即本次拆分执行追加的：优先按 id 精确命中；
            // 批内重复调用共享执行时响应携带的是首个调用者的 id，其余重复调用走首条兜底
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                if (call.id() != null && call.id().equals(response.id())) {
                    return response;
                }
            }
            return toolResponse.getResponses().isEmpty() ? null : toolResponse.getResponses().get(0);
        }
        return null;
    }

    /** 单调用执行失败的自愈响应：id 回传原 toolCall id（id=null 会让后续模型请求全部 NPE）；文案按可否重试分级 */
    private static ToolResponseMessage.ToolResponse errorResponse(AssistantMessage.ToolCall call, Exception error) {
        return new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                ToolErrorTexts.forExecutionFailure(error));
    }

    private static int countCalls(ChatResponse chatResponse) {
        if (chatResponse == null || !chatResponse.hasToolCalls()) {
            return 0;
        }
        int count = 0;
        for (Generation generation : chatResponse.getResults()) {
            if (generation.getOutput() != null && generation.getOutput().hasToolCalls()) {
                count += generation.getOutput().getToolCalls().size();
            }
        }
        return count;
    }

    private static long generationsWithCalls(ChatResponse chatResponse) {
        return chatResponse.getResults().stream()
                .filter(g -> g.getOutput() != null && g.getOutput().hasToolCalls())
                .count();
    }
}
