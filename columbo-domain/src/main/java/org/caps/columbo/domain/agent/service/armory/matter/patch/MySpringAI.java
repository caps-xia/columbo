package org.caps.columbo.domain.agent.service.armory.matter.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.models.springai.error.SpringAIErrorMapper;
import com.google.adk.models.springai.observability.SpringAIObservabilityHandler;
import com.google.adk.models.springai.properties.SpringAIProperties;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import org.caps.columbo.domain.agent.service.session.SessionTokenMeter;
import org.caps.columbo.domain.agent.service.session.StreamingMailbox;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.FlowableEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spring AI 补丁：相对官方 com.google.adk.models.springai.SpringAI 的改动点——
 * 1. 换用 MyMessageConverter（注入当前时间、清洗空 content、还原原始工具 schema）；
 * 2. 流式输出聚合改造：思考增量实时透传（官方 toLlmResponse 的 partial 判定对中文不友好）；
 * 3. 流式工具循环重建：Spring AI 1.1.5 的 stream() 内部会执行工具并递归重调，2.0.1 起
 *    ChatModel 不再内部执行工具（官方设计是透传 functionCall 交给 ADK 执行，但那会绕开
 *    SanitizingToolCallingManager 的 oneai 网关补丁），故在 generateStreamingContent
 *    里按 1.x 旧模式自行执行工具并续流。
 * 4. 工具循环守护（ToolLoopGuard）：连续相同调用=真死循环快速熔断；参数逐轮不同的长排查
 *    （如无时间锚点分段回扫日志）只受总轮数上限约束，且熔断时保留已产出的阶段性结果收尾，
 *    不再整流报错清空（2026-09-18 线上：13 分钟回扫定位成功前一刻被 20 轮上限清空）。
 * 5. 流停滞超时 + 安全重试 + 非流式兜底：接受 stream=true 却长期不吐块（网关挂起）按停滞
 *    超时中断；中断且本轮零产出时才允许重试（已向下游转发过增量再重试会让前端看到重复
 *    内容），重试耗尽降级非流式兜底（提炼自 deepseek-harness 的"仅未转发才重试"语义）。
 * 6. 会话 token 计量：每轮模型调用把用量（含缓存命中拆分）按会话归属累加落库，成本可算清。
 * 升级 Spring AI / ADK 时以此为基准重新 diff 官方实现。
 * 仅保留本项目实际使用的 (ChatModel) 构造路径。
 *
 * @author caps
 * 2026/1/9 08:20
 */
@Slf4j
public class MySpringAI extends BaseLlm {

    /** 思考内容标记（reasoning_content）：前端识别后渲染成"思考中/已思考"折叠面板 */
    public static final String THINK_OPEN = "⟦think⟧";
    public static final String THINK_CLOSE = "⟦/think⟧";
    /** 思考总耗时标记（毫秒），前端优先用它显示"用时" */
    public static final String THINK_TIME_OPEN = "⟦thinktime⟧";
    public static final String THINK_TIME_CLOSE = "⟦/thinktime⟧";

    /** 思考/正文单次运行累积上限（字符）：超过即截断，防异常流把前端与落库内容撑爆 */
    private static final int MAX_BUFFER_CHARS = 500_000;

    /** 流中断安全重试上限（次/轮）：仅"本轮零产出"时允许，重试耗尽降级非流式兜底 */
    private static final int MAX_STALL_RETRIES = 2;

    /** 停滞超时默认值（秒）：大上下文深度思考下首 token 前排队可达 1~3 分钟，阈值须高于它 */
    public static final long DEFAULT_STALL_TIMEOUT_SECONDS = 240;

    /**
     * 单轮思考长度上限（字符）：正常深度排查单轮思考数千字符封顶，超过判定为异常超长
     * （2026-09-26 线上：模型在"并行两个调用"上复读数百次烧 token）
     */
    /** 单轮思考字符上限（超限止损） */
    static final int REASONING_ROUND_CAP = 30_000;

    /** 思考复读的静默重试预算：复读是模型随机故障，同一 prompt 换一次请求通常即恢复 */
    private static final int MAX_LOOP_RETRIES = 1;

    /** 复读检测：尾部短周期重复的最小单元长度 */
    private static final int LOOP_MIN_PERIOD = 4;

    /**
     * 流式工具循环守护：区分"真死循环"与"长排查"。
     * 真死循环=连续 N 轮完全相同的工具调用（名+参数一致，典型：工具报错后模型原样重试），快速熔断；
     * 长排查=每轮参数都不同（如分段回扫日志），只受总轮数上限约束。
     */
    private static final class ToolLoopGuard {

        /** 总轮数上限：一轮=模型一次以工具调用收尾的流（日志单查 4-16s，40 轮已是数十分钟量级） */
        private static final int MAX_ROUNDS = 40;

        /** 连续相同调用计数达到该值判定死循环（首次出现计 1） */
        private static final int MAX_SAME_STREAK = 3;

        private int rounds;
        private String lastSignature = "";
        private int sameStreak;

        /** @return null=放行继续；非 null=熔断原因（写进收尾提示与观测日志） */
        String admit(String signature) {
            rounds++;
            if (signature.equals(lastSignature)) {
                sameStreak++;
            } else {
                sameStreak = 1;
                lastSignature = signature;
            }
            if (sameStreak >= MAX_SAME_STREAK) {
                return "连续 " + sameStreak + " 轮发出完全相同的工具调用，判定为循环调用已中止"
                        + "（工具可能持续报错导致模型原样重试）";
            }
            if (rounds > MAX_ROUNDS) {
                return "工具调用轮数达到上限 " + MAX_ROUNDS + " 已中止";
            }
            return null;
        }

        /** 已执行的工具轮数（空响应收尾提示判断"是否有阶段性成果"用） */
        int rounds() {
            return rounds;
        }

        /** 当前连续相同调用次数（第 2 次时注入纠正提示，第 3 次熔断） */
        int streak() {
            return sameStreak;
        }
    }

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ObjectMapper objectMapper;
    private final MyMessageConverter messageConverter;
    private final SpringAIObservabilityHandler observabilityHandler;
    /** Spring AI 2.x 起 ChatModel 不再内部执行工具，流式工具循环由本类经它重建（见 generateStreamingContent） */
    private final ToolCallingManager toolCallingManager;
    /** 会话 token 计量器（可为 null——单测/无归属场景），每轮模型调用按会话累加 */
    private final SessionTokenMeter tokenMeter;
    /** 流停滞超时（秒）：相邻流块间隔超此值按挂起中断走安全重试；0/负数关闭 */
    private final long stallTimeoutSeconds;

    public MySpringAI(ChatModel chatModel) {
        this(chatModel, ToolCallingManager.builder().build(), null, null, DEFAULT_STALL_TIMEOUT_SECONDS);
    }

    public MySpringAI(ChatModel chatModel, ToolCallingManager toolCallingManager) {
        this(chatModel, toolCallingManager, null, null, DEFAULT_STALL_TIMEOUT_SECONDS);
    }

    public MySpringAI(ChatModel chatModel, ToolCallingManager toolCallingManager,
                      StreamingMailbox streamingMailbox) {
        this(chatModel, toolCallingManager, streamingMailbox, null, DEFAULT_STALL_TIMEOUT_SECONDS);
    }

    public MySpringAI(ChatModel chatModel, ToolCallingManager toolCallingManager,
                      StreamingMailbox streamingMailbox, SessionTokenMeter tokenMeter,
                      long stallTimeoutSeconds) {
        super(extractModelName(chatModel));
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel cannot be null");
        this.streamingChatModel =
                (chatModel instanceof StreamingChatModel) ? (StreamingChatModel) chatModel : null;
        this.objectMapper = new ObjectMapper();
        this.messageConverter = new MyMessageConverter(objectMapper);
        this.observabilityHandler =
                new SpringAIObservabilityHandler(createDefaultObservabilityConfig());
        this.toolCallingManager =
                Objects.requireNonNull(toolCallingManager, "toolCallingManager cannot be null");
        this.streamingMailbox = streamingMailbox;
        this.tokenMeter = tokenMeter;
        this.stallTimeoutSeconds = stallTimeoutSeconds;
    }

    /** 插入信箱：对话进行中用户的新指令在下一工具轮前注入（可为 null——单测等无插入场景） */
    private final StreamingMailbox streamingMailbox;

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (stream) {
            if (this.streamingChatModel == null) {
                return Flowable.error(new IllegalStateException("StreamingChatModel is not configured"));
            }

            return generateStreamingContent(llmRequest);
        } else {
            if (this.chatModel == null) {
                return Flowable.error(new IllegalStateException("ChatModel is not configured"));
            }

            return generateContent(llmRequest);
        }
    }

    private Flowable<LlmResponse> generateContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "chat");

        try {
            // 传模型默认 options：官方 1.9 约定，Prompt options 基于其构建以保持具体类型
            // （SA 2.0 的 OpenAI 模型会直接强转 Prompt options 为 OpenAiChatOptions）
            Prompt prompt = messageConverter.toLlmPrompt(llmRequest, chatModel.getOptions());
            observabilityHandler.logRequest(prompt.toString(), model());

            long callStart = System.currentTimeMillis();
            ChatResponse chatResponse = chatModel.call(prompt);
            // 思考内容（Spring AI 2.0 原生把网关 reasoning_content 映射进 AssistantMessage metadata）：
            // 先于正文输出，前端渲染为可折叠思考面板，耗时取本次 LLM 调用总时长
            java.util.List<LlmResponse> thinkResponses = new java.util.ArrayList<>();
            long totalThinkMs = 0;
            String reasoning = reasoningOf(chatResponse);
            if (reasoning != null && !reasoning.isBlank()) {
                thinkResponses.add(LlmResponse.builder()
                        .content(Content.fromParts(Part.fromText(THINK_OPEN + reasoning + THINK_CLOSE)))
                        .build());
                totalThinkMs = System.currentTimeMillis() - callStart;
            }
            LlmResponse llmResponse = messageConverter.toLlmResponse(chatResponse);

            observabilityHandler.logResponse(extractTextFromResponse(llmResponse), model());

            // Extract token counts if available
            int totalTokens = extractTokenCount(chatResponse);
            int inputTokens = extractInputTokenCount(chatResponse);
            int outputTokens = extractOutputTokenCount(chatResponse);

            observabilityHandler.recordSuccess(context, totalTokens, inputTokens, outputTokens);
            // 按会话归属落账（同步路径由 ChatService 在当前线程 bind 归属范围）
            if (tokenMeter != null) {
                tokenMeter.recordCurrent(model(), tokenUsageOf(chatResponse));
            }
            if (thinkResponses.isEmpty()) {
                return Flowable.just(llmResponse);
            }
            // 真实思考耗时（各轮 LLM 请求耗时之和），前端用于"已完成思考 · 用时 Ns"
            thinkResponses.add(LlmResponse.builder()
                    .content(Content.fromParts(Part.fromText(
                            THINK_TIME_OPEN + totalThinkMs + THINK_TIME_CLOSE)))
                    .build());
            thinkResponses.add(llmResponse);
            return Flowable.fromIterable(thinkResponses);
        } catch (Exception e) {
            observabilityHandler.recordError(context, e);
            SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(e);

            return Flowable.error(new RuntimeException(mappedError.getNormalizedMessage(), e));
        }
    }

    /**
     * 流式路径（RunConfig SSE 触发）：思考增量实时旁路、正文收尾一次性落会话。
     * 工具循环在本类内完成：Spring AI 1.1.5 的 ChatModel.stream() 会在内部执行工具并
     * 递归重调模型（本项目旧栈依赖此行为），但 2.0.1 起 OpenAiChatModel 只聚合 tool call
     * 响应、不再内部执行——官方 ADK 设计是让 functionCall 透传给 ADK 执行，但那会让
     * oneai 网关补丁（SanitizingToolCallingManager：{} 前缀参数清洗 / content null 422 /
     * 非法 JSON 参数自愈）全部失效。故这里按 1.x 旧模式重建内部循环：流内发现 tool call
     * 响应 → ToolCallingManager 执行 → 用其 conversationHistory 续流下一轮，直到模型
     * 返回纯文本。订阅侧依旧只会收到思考/正文增量，不会收到工具块——最终事件只含正文
     * 全量，不带 functionCall（带了会触发 ADK 侧二次执行工具）。
     * 官方 toLlmResponse(resp, true) 的 partial 判定是 ".!?\n" 句尾启发式，中文句号
     * 结尾的正文会被误判为 partial 而不落会话，故这里自行聚合、不用官方转换。
     */
    private Flowable<LlmResponse> generateStreamingContent(LlmRequest llmRequest) {
        SpringAIObservabilityHandler.RequestContext context =
                observabilityHandler.startRequest(model(), "streaming");
        // 插入信箱句柄捕获（首轮在同线程）：对话进行中用户的插入指令在工具轮间注入
        StreamingMailbox.RunHandle run = streamingMailbox == null ? null : streamingMailbox.current();

        return Flowable.create(
                emitter -> {
                    StringBuilder textBuf = new StringBuilder();
                    // 思考文本累积：partial 增量只进前端不落库，最终事件需携带完整思考标记
                    // （历史会话恢复时前端 parseThinkMarkers 靠它还原折叠思考面板）
                    StringBuilder thinkBuf = new StringBuilder();
                    // 上一块原始 reasoningContent 与累积语义标记（见 thinkDeltaOf）
                    String[] lastRawReasoning = {""};
                    boolean[] cumulativeReasoning = {false};
                    // 思考面板开合状态（lambda 捕获需可变）：正文首块到达即闭合，后续轮次思考可再开新面板
                    boolean[] thinkOpen = {false};
                    long[] thinkStart = {0};
                    // 各缓冲是否已触发截断（warn 只打一次）
                    boolean[] thinkTruncated = {false};
                    boolean[] textTruncated = {false};
                    // 工具循环守护（真死循环快断 / 长排查轮数上限）
                    ToolLoopGuard toolLoop = new ToolLoopGuard();
                    // 本轮 finishReason（length=输出被长度上限截断）
                    String[] lastFinishReason = {null};
                    // 思考复读/超长中止后置位：忽略残余 chunk，complete/error 也不再处理
                    boolean[] streamAborted = {false};
                    // 空响应轮重试标记：模型轮无正文/思考/工具时原样重试一次（网关偶发空响应）
                    boolean[] emptyRoundRetried = {false};
                    // 流中断安全重试计数（turn 级）：仅"本轮零产出"允许重试；有产出的轮复位
                    int[] stallRetries = {0};
                    // 思考复读静默重试计数：复读是模型随机故障，重试不算中断也不附加系统提示
                    int[] loopRetries = {0};
                    // 当前轮的底层订阅（递归续流时轮换）：外层 dispose 时取消的是它
                    AtomicReference<reactor.core.Disposable> currentDisposable = new AtomicReference<>();
                    try {
                        Prompt prompt = messageConverter.toLlmPrompt(llmRequest, chatModel.getOptions());
                        streamRound(emitter, prompt, textBuf, thinkBuf, lastRawReasoning,
                                cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run, stallRetries, loopRetries, currentDisposable, context);
                        // 挂接取消传播：外层 Flowable 被 dispose（如客户端断开 SSE）时同步取消
                        // 当前轮 LLM 订阅，避免请求线程已走、模型仍在后台空烧
                        emitter.setCancellable(() -> {
                            reactor.core.Disposable disposable = currentDisposable.get();
                            if (disposable != null && !disposable.isDisposed()) {
                                disposable.dispose();
                            }
                        });
                    } catch (Exception e) {
                        emitError(emitter, context, e);
                    }
                },
                BackpressureStrategy.BUFFER);
    }

    /** 单轮模型流：聚合思考/正文增量，流末发现 tool call 则执行工具并递归续流下一轮 */
    private void streamRound(FlowableEmitter<LlmResponse> emitter, Prompt prompt,
                             StringBuilder textBuf, StringBuilder thinkBuf,
                             String[] lastRawReasoning, boolean[] cumulativeReasoning,
                             boolean[] thinkOpen, long[] thinkStart, boolean[] thinkTruncated,
                             boolean[] textTruncated, ToolLoopGuard toolLoop, boolean[] emptyRoundRetried,
                             String[] lastFinishReason, boolean[] streamAborted,
                             StreamingMailbox.RunHandle run, int[] stallRetries, int[] loopRetries,
                             AtomicReference<reactor.core.Disposable> currentDisposable,
                             SpringAIObservabilityHandler.RequestContext context) {
        // 用户停止后不再发起新一轮模型请求（工具执行完→续流的窗口由这里兜住），按阶段性结果收尾
        if (run != null && run.cancelled()) {
            closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen, thinkStart, textTruncated,
                    context, "用户停止", "已按要求停止生成");
            return;
        }
        observabilityHandler.logRequest(prompt.toString(), model());
        // 每轮复位 finishReason：防止前面工具轮出现 length 后，后续未回该字段的正文轮被误判截断
        lastFinishReason[0] = null;
        // 本轮起点快照：判断该轮是否产出内容（空响应检测用，缓冲是跨轮累积的）
        final int textStart = textBuf.length();
        final int thinkStartLen = thinkBuf.length();
        // 思考面板计时起点 = 本轮模型请求发出时刻（而非首个思考增量到达）：
        // 大上下文深度思考下首 token 前的排队/预处理常达 1~3 分钟，用户体感就是"在想"，
        // 从首增量起算会显著偏小（2026-09-22 用户反馈"想了很久只显示 35.6s"）
        final long roundStartMs = System.currentTimeMillis();
        // 本轮收到的 tool call 响应（2.0.1 的 ChunkMerger 会把 tool call 增量聚合成单个完整响应）
        List<ChatResponse> toolCallResponses = new ArrayList<>();
        // 本轮是否已向下游转发过增量：安全重试只在"本轮零产出"时允许，已转发再重试
        // 会让前端看到重复的思考流（提炼自 deepseek-harness 的"仅未转发才重试"语义）
        boolean[] roundEmitted = {false};
        // 本轮用量（网关在流末 chunk 携带 usage，取最后一个非空值，轮末按会话归属落账）
        Usage[] roundUsage = {null};

        reactor.core.publisher.Flux<ChatResponse> flux = streamingChatModel.stream(prompt);
        // 停滞超时：接受 stream=true 却长期不吐块（网关挂起/断流）按超时中断走安全重试，
        // 而不是干等 HTTP 客户端 300s 超时；大上下文首 token 排队可达 1~3 分钟，默认阈值高于它
        if (stallTimeoutSeconds > 0) {
            flux = flux.timeout(Duration.ofSeconds(stallTimeoutSeconds));
        }
        reactor.core.Disposable disposable = flux
                .subscribe(
                        chatResponse -> {
                            if (streamAborted[0]) {
                                return;
                            }
                            // 用户停止（Stop 按钮 → /chat_stop 置标志）：SSE dispose 经 FlowableCache
                            // 传不进模型流（2026-10-05 线上：停止后后台空烧到模型超长截断），只能
                            // 靠标志位主动停——停掉当前模型订阅，已有思考/正文按阶段性结果收尾落库
                            if (run != null && run.cancelled()) {
                                streamAborted[0] = true;
                                reactor.core.Disposable d = currentDisposable.get();
                                if (d != null && !d.isDisposed()) {
                                    d.dispose();
                                }
                                closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen, thinkStart,
                                        textTruncated, context, "用户停止", "已按要求停止生成");
                                return;
                            }
                            try {
                                // 思考增量（流式路径 Spring AI 会把 reasoning_content
                                // 放进 chunk 的 AssistantMessage metadata）：partial 事件不落会话、只透传前端
                                String reasoning = reasoningOf(chatResponse);
                                if (reasoning != null && !reasoning.isBlank()) {
                                    String thinkDelta = thinkDeltaOf(reasoning, lastRawReasoning, cumulativeReasoning);
                                    if (!thinkDelta.isEmpty()) {
                                        roundEmitted[0] = true;
                                        if (!thinkOpen[0]) {
                                            thinkOpen[0] = true;
                                            thinkStart[0] = roundStartMs;
                                            emitter.onNext(partialText(THINK_OPEN));
                                            // 开标记同步入 buf：final 内容自带分段标记，历史恢复可还原
                                            // 逐段思考与各轮耗时（2026-10-05 线上：整场合并成 1 块、
                                            // thinktime 只剩最后一轮时长，前端"2 段/59.9s"严重失真）
                                            thinkBuf.append(THINK_OPEN);
                                        }
                                        appendCapped(thinkBuf, thinkDelta, thinkTruncated, "思考");
                                        emitter.onNext(partialText(thinkDelta));
                                    }
                                    // 思考复读/碎片化/超长守护：任一命中 → 立即中止止损（不再烧 token）
                                    int roundThinkLen = thinkBuf.length() - thinkStartLen;
                                    boolean thinkOverCap = roundThinkLen > REASONING_ROUND_CAP;
                                    boolean thinkLoop = reasoningLoopDetected(thinkBuf, thinkStartLen)
                                            || reasoningFragmentationDetected(thinkBuf, thinkStartLen);
                                    if (thinkOverCap || thinkLoop) {
                                        // 复读（非超长）且重试预算未用完：复读是模型随机故障，同一 prompt
                                        // 换一次请求通常即恢复——静默重试（2026-10-06 线上：计划句整句重复
                                        // 3 次被检测掐断，整场以"已中止"残局告终）。旧思考段就地闭合成独立
                                        // 分段（前端新开面板），重试不算中止、不附加系统提示；再复读才收尾
                                        if (!thinkOverCap && loopRetries[0] < MAX_LOOP_RETRIES) {
                                            loopRetries[0]++;
                                            log.warn("模型思考陷入重复/碎片化循环（本轮 {} 字符），静默重试 {}/{}",
                                                    roundThinkLen, loopRetries[0], MAX_LOOP_RETRIES);
                                            if (thinkOpen[0]) {
                                                emitter.onNext(partialText(closeThink(thinkStart[0])));
                                                thinkBuf.append(closeThink(thinkStart[0]));
                                                thinkOpen[0] = false;
                                            }
                                            reactor.core.Disposable d = currentDisposable.get();
                                            if (d != null && !d.isDisposed()) {
                                                d.dispose();
                                            }
                                            streamRound(emitter, prompt, textBuf, thinkBuf, lastRawReasoning,
                                                    cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                                    textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run,
                                                    stallRetries, loopRetries, currentDisposable, context);
                                            return;
                                        }
                                        streamAborted[0] = true;
                                        log.warn("模型思考{}，已中止（本轮思考 {} 字符/正文 {} 字符）",
                                                thinkOverCap ? "超长" : "陷入重复循环",
                                                roundThinkLen, textBuf.length());
                                        if (thinkOpen[0]) {
                                            emitter.onNext(partialText(closeThink(thinkStart[0])));
                                            thinkBuf.append(closeThink(thinkStart[0]));
                                            thinkOpen[0] = false;
                                        }
                                        // 两种触发原因分开提示：复读是模型行为异常，建议重发；超长是单轮预算止损，"继续"即可接续
                                        appendCapped(textBuf,
                                                (textBuf.length() == 0 ? "" : "\n\n")
                                                        + (thinkOverCap
                                                        ? "【系统提示】模型本轮思考超长已止损（单轮 " + REASONING_ROUND_CAP + " 字符上限），以上为已生成部分；发送“继续”可接着分析。"
                                                        : "【系统提示】模型思考陷入重复循环已中止，以上为已生成部分；重新发送本问题或说“继续”可接着排查。"),
                                                textTruncated, "正文");
                                        observabilityHandler.recordError(context,
                                                new IllegalStateException("模型思考复读/超长，已中止"));
                                        reactor.core.Disposable d = currentDisposable.get();
                                        if (d != null && !d.isDisposed()) {
                                            d.dispose();
                                        }
                                        emitFinal(emitter, textBuf, thinkBuf, thinkStart);
                                        return;
                                    }
                                }
                                String text = textOf(chatResponse);
                                if (!text.isEmpty()) {
                                    // 正文首块到达即闭合思考面板：前端 parseThink 对未闭合的
                                    // think 标记会把后续正文全部吞进思考区
                                    if (thinkOpen[0]) {
                                        emitter.onNext(partialText(closeThink(thinkStart[0])));
                                        thinkBuf.append(closeThink(thinkStart[0]));
                                        thinkOpen[0] = false;
                                    }
                                    appendCapped(textBuf, text, textTruncated, "正文");
                                    // 正文增量实时透传（final 事件仍带全量落会话；SSE 侧按已
                                    // 转发量去重防重复）——长报告生成期间用户不再干等整段蹦出
                                    roundEmitted[0] = true;
                                    if (!textTruncated[0]) {
                                        emitter.onNext(partialText(text));
                                    }
                                }
                                if (chatResponse.hasToolCalls()) {
                                    toolCallResponses.add(chatResponse);
                                }
                                // 记录本轮 finishReason：length=输出被长度上限截断（收尾时提示，而非静默断尾）
                                try {
                                    if (chatResponse.getResult() != null && chatResponse.getResult().getMetadata() != null) {
                                        String fr = chatResponse.getResult().getMetadata().getFinishReason();
                                        if (fr != null && !fr.isBlank()) {
                                            lastFinishReason[0] = fr;
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // 网关不回 finishReason 时跳过，不影响主流程
                                }
                                // 记录本轮 usage（流末 chunk 携带）：只认"有真实数值"的 usage——
                                // 网关不回 usage 的 chunk，metadata 默认填 EmptyUsage（非 null 全 0），
                                // 直接覆盖会把前面真实用量清掉（单测探针实测）
                                try {
                                    if (chatResponse.getMetadata() != null) {
                                        Usage usage = chatResponse.getMetadata().getUsage();
                                        if (isMeaningfulUsage(usage)) {
                                            roundUsage[0] = usage;
                                        }
                                    }
                                } catch (Exception ignored) {
                                    // 网关不回 usage 时跳过，不影响主流程
                                }
                            } catch (Exception e) {
                                emitError(emitter, context, e);
                            }
                        },
                        error -> {
                            if (streamAborted[0]) {
                                return;
                            }
                            // 安全重试：仅当本轮零产出（未转发过增量、两个缓冲本轮都没增长）——
                            // 网关断流/5xx/停滞超时重试才不会让前端看到重复内容；已产出维持整流报错
                            boolean pristineRound = !roundEmitted[0]
                                    && textBuf.length() == textStart && thinkBuf.length() == thinkStartLen;
                            if (pristineRound && stallRetries[0] < MAX_STALL_RETRIES) {
                                stallRetries[0]++;
                                log.warn("模型流未产出任何内容即中断（{}），安全重试 {}/{}",
                                        error.toString(), stallRetries[0], MAX_STALL_RETRIES);
                                streamRound(emitter, prompt, textBuf, thinkBuf, lastRawReasoning,
                                        cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                        textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run,
                                        stallRetries, loopRetries, currentDisposable, context);
                                return;
                            }
                            if (pristineRound) {
                                // 重试耗尽仍无产出：降级非流式兜底（网关偶发"接受流式却永不吐块"），
                                // 阻塞调用搬到 IO 线程，产物沿用 emitFinal 落会话
                                log.warn("流式安全重试耗尽仍无产出（{}），降级非流式兜底", error.toString());
                                runNonStreamFallback(emitter, prompt, run, context, textBuf, thinkBuf,
                                        thinkStart, thinkTruncated, textTruncated);
                                return;
                            }
                            // 非零产出轮中断（网络断/网关死亡等）：与熔断同一哲学——已有积累不报错清场，
                            // 保阶段性结果正常落库收尾，用户发"继续"即从最近完整状态续查；
                            // 零积累（首轮即断且无工具轮）没有可保留的东西，照旧整流报错
                            if (textBuf.length() > 0 || thinkBuf.length() > 0 || toolLoop.rounds() > 0) {
                                closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen,
                                        thinkStart, textTruncated, context, "流式中断",
                                        "模型流式连接中断（" + error.getMessage() + "），发送\"继续\"可接着排查");
                                return;
                            }
                            emitError(emitter, context, error);
                        },
                        () -> {
                            if (streamAborted[0]) {
                                return;
                            }
                            try {
                                // 轮末落账本轮用量（网关在流末 chunk 带 usage；空流无 usage 自动跳过）
                                if (tokenMeter != null && roundUsage[0] != null) {
                                    tokenMeter.record(run, model(), tokenUsageOf(roundUsage[0]));
                                    roundUsage[0] = null;
                                }
                                if (!toolCallResponses.isEmpty()) {
                                    // 下游已取消（SSE 超时/客户端断连后 dispose 传播到位）：
                                    // 不再执行工具、不再续流——否则取消发生在"工具执行→递归续流"
                                    // 间隙时，新一轮订阅无人取消，模型会在后台空烧到自然结束
                                    //（2026-09-18 线上：超时后后台又跑了 3 分多钟）
                                    if (emitter.isCancelled()) {
                                        return;
                                    }
                                    // 用户停止：不再执行待发工具/不再续流（探量查库动辄数秒），按阶段性结果收尾
                                    if (run != null && run.cancelled()) {
                                        closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen,
                                                thinkStart, textTruncated, context, "用户停止", "已按要求停止生成");
                                        return;
                                    }
                                    // 模型本轮以 tool call 收尾：先闭合思考面板再进工具执行
                                    //（后续轮次若还有思考会开新面板，"用时"按各自面板计）
                                    if (thinkOpen[0]) {
                                        emitter.onNext(partialText(closeThink(thinkStart[0])));
                                        thinkBuf.append(closeThink(thinkStart[0]));
                                        thinkOpen[0] = false;
                                    }
                                    String abortReason = toolLoop.admit(toolCallSignature(toolCallResponses));
                                    if (abortReason != null) {
                                        closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen,
                                                thinkStart, textTruncated, context, "流式工具循环熔断", abortReason);
                                        return;
                                    }
                                    // 本轮带工具调用=有产出：复位空响应重试标记与安全重试计数
                                    emptyRoundRetried[0] = false;
                                    stallRetries[0] = 0;
                                    Prompt nextPrompt = prompt;
                                    boolean returnDirect = false;
                                    if (toolLoop.streak() >= 2) {
                                        // 连续第 2+ 次完全相同调用：确定性拦截，不再真实执行——
                                        // 相同入参结果不会变（policy 本就禁止同条件重查），把拦截事实作为
                                        // 工具响应回传，逼模型改参数/换工具/收敛结论。纠正提示（UserMessage）
                                        // 是"劝"，模型可不听（2026-10-05 18:05 线上：注入后仍原样重发）；
                                        // 拦截执行是"断"，第 3 次仍相同由上方熔断兜底
                                        List<org.springframework.ai.chat.messages.Message> history =
                                                new ArrayList<>(prompt.getInstructions());
                                        List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse>
                                                rejections = new ArrayList<>();
                                        for (ChatResponse toolCallResponse : toolCallResponses) {
                                            if (toolCallResponse.getResult() == null
                                                    || toolCallResponse.getResult().getOutput() == null) {
                                                continue;
                                            }
                                            history.add(toolCallResponse.getResult().getOutput().getText() == null
                                                    ? org.springframework.ai.chat.messages.AssistantMessage.builder()
                                                            .content("")
                                                            .properties(toolCallResponse.getResult().getOutput().getMetadata())
                                                            .toolCalls(toolCallResponse.getResult().getOutput().getToolCalls())
                                                            .media(toolCallResponse.getResult().getOutput().getMedia())
                                                            .build()
                                                    : toolCallResponse.getResult().getOutput());
                                            for (AssistantMessage.ToolCall call
                                                    : toolCallResponse.getResult().getOutput().getToolCalls()) {
                                                rejections.add(new org.springframework.ai.chat.messages
                                                        .ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                                        "工具未执行：本次调用与上一轮完全相同（同名同参）已拦截。"
                                                        + "相同入参结果不会变化，上一轮结果已在你的上下文中；"
                                                        + "若信息不足，请修改参数/关键词/时间范围后重试，或换用其他工具；"
                                                        + "若已无新证据可查，请直接基于已有证据输出结论。"));
                                            }
                                        }
                                        history.add(org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                                                .responses(rejections).build());
                                        nextPrompt = new Prompt(history, prompt.getOptions());
                                        log.info("已拦截重复工具调用（连续 {} 次相同，本次不执行）", toolLoop.streak());
                                        streamRound(emitter, nextPrompt, textBuf, thinkBuf, lastRawReasoning,
                                                cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                                textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run,
                                                stallRetries, loopRetries, currentDisposable, context);
                                        return;
                                    }
                                    for (ChatResponse toolCallResponse : toolCallResponses) {
                                        // 工具执行期绑定调用用户上下文（会话归属判定等）
                                        // 用户级路由靠它定位本人的 SSE 活跃流（并行执行由
                                        // ParallelToolCallingManager 在提交任务时捕获传播）
                                        if (run != null) {
                                            org.caps.columbo.domain.agent.service.context.AgentCallContext.bind(run.userId());
                                        }
                                        try {
                                            ToolExecutionResult result =
                                                    toolCallingManager.executeToolCalls(nextPrompt, toolCallResponse);
                                            nextPrompt = new Prompt(result.conversationHistory(), prompt.getOptions());
                                            returnDirect = result.returnDirect();
                                        } finally {
                                            org.caps.columbo.domain.agent.service.context.AgentCallContext.clear();
                                        }
                                    }
                                    if (returnDirect) {
                                        // returnDirect 工具不回模型，直接收束本轮
                                        observabilityHandler.recordSuccess(context, 0, 0, 0);
                                        emitter.onComplete();
                                        return;
                                    }
                                    // 工具执行期间下游取消（工具回调耗时数秒，是最常见的取消窗口）
                                    if (emitter.isCancelled()) {
                                        return;
                                    }
                                    // 用户停止：不再执行待发工具/不再续流（探量查库动辄数秒），按阶段性结果收尾
                                    if (run != null && run.cancelled()) {
                                        closeWithPartialResult(emitter, textBuf, thinkBuf, thinkOpen,
                                                thinkStart, textTruncated, context, "用户停止", "已按要求停止生成");
                                        return;
                                    }
                                    // 插入式对话：把用户在流式期间插入的指令作为 user 消息
                                    // 拼到工具结果之后，下一轮模型立即可见（ZCode 式中途调整）
                                    if (run != null) {
                                        List<String> injected = streamingMailbox.drain(run);
                                        for (String msg : injected) {
                                            List<org.springframework.ai.chat.messages.Message> history =
                                                    new ArrayList<>(nextPrompt.getInstructions());
                                            history.add(new org.springframework.ai.chat.messages.UserMessage(msg));
                                            nextPrompt = new Prompt(history, nextPrompt.getOptions());
                                            log.info("已注入用户插入消息到下一轮对话（{} 字符）", msg.length());
                                        }
                                    }
                                    // 带上工具结果续流下一轮（工具执行/参数清洗补丁都在
                                    // SanitizingToolCallingManager 里，经构造器注入生效）
                                    streamRound(emitter, nextPrompt, textBuf, thinkBuf, lastRawReasoning,
                                            cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                            textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run,
                                            stallRetries, loopRetries, currentDisposable, context);
                                    return;
                                }
                                if (thinkOpen[0]) {
                                    emitter.onNext(partialText(closeThink(thinkStart[0])));
                                    thinkBuf.append(closeThink(thinkStart[0]));
                                    thinkOpen[0] = false;
                                }
                                // 输出被长度上限截断（finishReason=length）：重试同样会撞上限，
                                // 保留已生成部分并明确提示，而非静默断尾（2026-09-26 线上：思考被
                                // 大段外部内容吃掉输出预算，正文停在半句话无报错）
                                if ("length".equalsIgnoreCase(lastFinishReason[0])) {
                                    log.warn("模型输出因长度上限截断 finishReason=length（正文 {} 字符/思考 {} 字符）",
                                            textBuf.length(), thinkBuf.length());
                                    appendCapped(textBuf,
                                            (textBuf.length() == 0 ? "" : "\n\n")
                                                    + "【系统提示】模型输出因长度上限被截断，以上为已生成部分；发送“继续”可接着分析。",
                                            textTruncated, "正文");
                                    observabilityHandler.recordError(context,
                                            new IllegalStateException("模型输出因长度上限截断"));
                                    emitFinal(emitter, textBuf, thinkBuf, thinkStart);
                                    return;
                                }
                                // 异常完成轮：本轮无正文产出（空响应=啥都没有；仅思考无正文=输出了
                                // reasoning 却没给答案/工具调用，2026-09-26 线上：回答停在半句话、
                                // 思考后被无关内容占满）。先原样重试一轮；连续两次按异常中断收尾。
                                if (toolCallResponses.isEmpty() && textBuf.length() == textStart) {
                                    String kind = thinkBuf.length() == thinkStartLen ? "空响应" : "仅思考无正文";
                                    if (!emptyRoundRetried[0]) {
                                        emptyRoundRetried[0] = true;
                                        log.warn("模型轮{}，原样重试一次（累计正文 {} 字符）", kind, textBuf.length());
                                        streamRound(emitter, prompt, textBuf, thinkBuf, lastRawReasoning,
                                                cumulativeReasoning, thinkOpen, thinkStart, thinkTruncated,
                                                textTruncated, toolLoop, emptyRoundRetried, lastFinishReason, streamAborted, run,
                                                stallRetries, loopRetries, currentDisposable, context);
                                        return;
                                    }
                                    log.warn("模型轮连续两次{}，按异常中断收尾（正文 {} 字符/思考 {} 字符/已执行工具 {} 轮）",
                                            kind, textBuf.length(), thinkBuf.length(), toolLoop.rounds());
                                    if (textBuf.length() > 0 || thinkBuf.length() > 0 || toolLoop.rounds() > 0) {
                                        appendCapped(textBuf,
                                                (textBuf.length() == 0 ? "" : "\n\n")
                                                        + "【系统提示】模型输出异常中断（" + kind + "），以上为已完成的阶段性结果；重新发送本问题可从中断处继续。",
                                                textTruncated, "正文");
                                        observabilityHandler.recordError(context,
                                                new IllegalStateException("模型连续两次" + kind + "，流式回答被截断"));
                                        emitFinal(emitter, textBuf, thinkBuf, thinkStart);
                                    } else {
                                        emitError(emitter, context,
                                                new IllegalStateException("模型返回" + kind + "，请稍后重试"));
                                    }
                                    return;
                                }
                                // 本轮有产出：重置空响应重试标记与安全重试计数（长排查多轮中偶发空响应各可重试一次）
                                emptyRoundRetried[0] = false;
                                stallRetries[0] = 0;
                                // 污染探针：中文会话里单轮思考几乎全英文且较长 → 可疑（网关串流/注入回声），
                                // 记 WARN 供按时间戳到网关侧核对，不影响流程
                                int roundThinkLenProbe = thinkBuf.length() - thinkStartLen;
                                if (roundThinkLenProbe > 500) {
                                    String roundThink = thinkBuf.substring(thinkStartLen);
                                    long cjk = roundThink.chars().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
                                    if (cjk * 5 < roundThinkLenProbe) {
                                        log.warn("模型思考非中文占比异常（疑似污染/外语回声） len={} cjk={}——请按时间戳核对网关侧响应",
                                                roundThinkLenProbe, cjk);
                                    }
                                }
                                emitFinal(emitter, textBuf, thinkBuf, thinkStart);
                                observabilityHandler.recordSuccess(context, 0, 0, 0);
                            } catch (Exception e) {
                                emitError(emitter, context, e);
                            }
                        });
        currentDisposable.set(disposable);
        // 竞态兜底：取消若发生在本轮订阅建立之前（cancellable 只在取消那一刻读 currentDisposable，
        // 错过了刚 set 的新一轮），这里补一刀，保证新订阅不会漏取消
        if (emitter.isCancelled() && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /** 观测记录 + 归一化错误消息后透传给订阅者（流终止） */
    private void emitError(FlowableEmitter<LlmResponse> emitter,
                           SpringAIObservabilityHandler.RequestContext context, Throwable error) {
        observabilityHandler.recordError(context, error);
        SpringAIErrorMapper.MappedError mappedError = SpringAIErrorMapper.mapError(error);
        emitter.onError(new RuntimeException(mappedError.getNormalizedMessage(), error));
    }

    /**
     * 非流式兜底（流式安全重试耗尽仍零产出时）：Prompt 级重调（工具结果已在
     * conversationHistory 里），带有界工具循环（3 跳封顶——有界本身就是护栏，不再挂
     * ToolLoopGuard）。阻塞调用经 RxJava Schedulers.io 搬到 IO 线程执行，避免阻塞网关
     * 回调线程；产物沿用 emitFinal 落会话，前端无需感知"这轮走的是兜底路径"。
     * 场景：网关偶发"接受 stream=true 却永不吐块"，流式通道坏了非流式仍正常。
     */
    private void runNonStreamFallback(FlowableEmitter<LlmResponse> emitter, Prompt prompt,
                                      StreamingMailbox.RunHandle run,
                                      SpringAIObservabilityHandler.RequestContext context,
                                      StringBuilder textBuf, StringBuilder thinkBuf, long[] thinkStart,
                                      boolean[] thinkTruncated, boolean[] textTruncated) {
        Flowable.<Void>create(e -> {
            try {
                Prompt nextPrompt = prompt;
                for (int hop = 0; hop < 3 && !emitter.isCancelled(); hop++) {
                    observabilityHandler.logRequest(nextPrompt.toString(), model());
                    long callStart = System.currentTimeMillis();
                    ChatResponse response = chatModel.call(nextPrompt);
                    if (tokenMeter != null) {
                        tokenMeter.record(run, model(), tokenUsageOf(response));
                    }
                    String reasoning = reasoningOf(response);
                    if (reasoning != null && !reasoning.isBlank()) {
                        appendCapped(thinkBuf, (thinkBuf.length() == 0 ? "" : "\n") + reasoning,
                                thinkTruncated, "思考");
                    }
                    if (response.hasToolCalls()) {
                        // 模型以工具调用收尾：执行工具后带结果继续非流式重调（有界 3 跳）
                        // 工具执行期绑定调用用户上下文（与流式路径同语义，IO 线程上工具仍可路由）
                        if (run != null) {
                            org.caps.columbo.domain.agent.service.context.AgentCallContext.bind(run.userId());
                        }
                        ToolExecutionResult result;
                        try {
                            result = toolCallingManager.executeToolCalls(nextPrompt, response);
                        } finally {
                            org.caps.columbo.domain.agent.service.context.AgentCallContext.clear();
                        }
                        if (result.returnDirect()) {
                            break;
                        }
                        nextPrompt = new Prompt(result.conversationHistory(), prompt.getOptions());
                        continue;
                    }
                    String text = textOf(response);
                    if (!text.isEmpty()) {
                        appendCapped(textBuf, text, textTruncated, "正文");
                    }
                    TokenUsageVO usage = tokenUsageOf(response);
                    observabilityHandler.recordSuccess(context,
                            usage == null ? 0 : (int) usage.getTotalTokens(),
                            usage == null ? 0 : (int) usage.getInputTokens(),
                            usage == null ? 0 : (int) usage.getOutputTokens());
                    emitFinal(emitter, textBuf, thinkBuf, thinkStart);
                    return;
                }
                // 3 跳都以工具调用收尾（或 returnDirect）：有界兜底到此为止，按阶段性结果收尾
                log.warn("非流式兜底 {} 跳内未收敛到纯文本，按阶段性结果收尾（正文 {} 字符）",
                        3, textBuf.length());
                emitFinal(emitter, textBuf, thinkBuf, thinkStart);
            } catch (Exception ex) {
                // 兜底也中断：已有积累同样保阶段性结果收尾（可续传），零积累才报错
                if (textBuf.length() > 0 || thinkBuf.length() > 0) {
                    closeWithPartialResult(emitter, textBuf, thinkBuf, new boolean[]{false},
                            thinkStart, textTruncated, context, "非流式兜底中断",
                            "非流式兜底重调也中断（" + ex.getMessage() + "），发送\"继续\"可接着排查");
                    return;
                }
                emitError(emitter, context, ex);
            } finally {
                e.onComplete();
            }
        }, BackpressureStrategy.BUFFER)
                .subscribeOn(io.reactivex.rxjava3.schedulers.Schedulers.io())
                .subscribe();
    }

    /**
     * 保阶段性结果收尾（通用）：熔断（ToolLoopGuard）与流式中断共用——不整流报错清空，
     * 已聚合的思考/正文作为阶段性结论正常落会话并收尾，附加系统提示说明原因；
     * 观测侧仍记异常，保留可追溯性。中断场景下发"继续"即可从最近完整状态续查。
     */
    private void closeWithPartialResult(FlowableEmitter<LlmResponse> emitter, StringBuilder textBuf,
                                        StringBuilder thinkBuf, boolean[] thinkOpen, long[] thinkStart,
                                        boolean[] textTruncated, SpringAIObservabilityHandler.RequestContext context,
                                        String source, String reason) {
        log.warn("{}：{}；已产出正文 {} 字符/思考 {} 字符，按阶段性结果收尾",
                source, reason, textBuf.length(), thinkBuf.length());
        // 中断发生在思考中途时先向 partial 流补发思考闭合标记：SSE 侧解析器的 inThink
        // 状态只靠标记翻转，缺闭合标记会把 final 剥掉思考标记后的正文（含系统提示）
        // 整段当思考增量推给前端——正文被吞进思考面板（2026-10-05 排查实证）
        if (thinkOpen[0]) {
            emitter.onNext(partialText(closeThink(thinkStart[0])));
            thinkBuf.append(closeThink(thinkStart[0]));
            thinkOpen[0] = false;
        }
        String notice = (textBuf.length() == 0 ? "" : "\n\n") + "【系统提示】" + reason
                + "。以上为此前已完成的阶段性结果，可补充信息（如时间范围、traceId）后继续排查。";
        appendCapped(textBuf, notice, textTruncated, "正文");
        // 用户主动停止不是异常：方法开头的 warn 已留痕（来源+原因+产出量），
        // 不再走 recordError——否则每次点停止都打一条 ERROR 级"Request failed"
        // 带完整堆栈（2026-10-24 线上噪音）。真正的中断/熔断仍记 ERROR 保留可追溯性。
        if (!"用户停止".equals(source)) {
            observabilityHandler.recordError(context, new IllegalStateException(reason));
        }
        emitFinal(emitter, textBuf, thinkBuf, thinkStart);
    }

    /**
     * 最终事件（不带 partial → 落会话、判定 finalResponse 结束本轮）：思考标记+正文全量——
     * 思考由此落库（历史恢复可还原折叠面板），前端不重复：Controller 对非 partial 事件
     * 剥掉思考标记只转发正文。空内容不发事件（落库空消息只会污染会话）。
     */
    private static void emitFinal(FlowableEmitter<LlmResponse> emitter, StringBuilder textBuf,
                                  StringBuilder thinkBuf, long[] thinkStart) {
        if (textBuf.length() == 0 && thinkBuf.length() == 0) {
            emitter.onComplete();
            return;
        }
        String finalText = textBuf.toString();
        if (thinkBuf.length() > 0) {
            // 流式路径各轮开合标记已实时嵌入 thinkBuf：直接拼装，历史解析可还原 N 段思考
            // 与各轮真实耗时；仅非流式兜底（buf 为无标记纯文本）整体包裹一块
            finalText = thinkBuf.indexOf(THINK_OPEN) >= 0
                    ? thinkBuf + finalText
                    : THINK_OPEN + thinkBuf + closeThink(thinkStart[0]) + finalText;
        }
        emitter.onNext(LlmResponse.builder()
                .content(Content.builder().role("model")
                        .parts(List.of(Part.fromText(finalText)))
                        .build())
                .build());
        emitter.onComplete();
    }

    /** 本轮工具调用签名（名+参数逐个拼接），供 ToolLoopGuard 识别"完全相同的调用"连续重复 */
    private static String toolCallSignature(List<ChatResponse> toolCallResponses) {
        StringBuilder signature = new StringBuilder();
        for (ChatResponse response : toolCallResponses) {
            if (response.getResult() == null || response.getResult().getOutput() == null) {
                continue;
            }
            for (AssistantMessage.ToolCall call : response.getResult().getOutput().getToolCalls()) {
                signature.append(call.name()).append('(').append(call.arguments()).append(");");
            }
        }
        return signature.toString();
    }

    /** 思考面板闭合标记 + 该面板从首个思考增量到闭合的真实耗时（毫秒） */
    private static String closeThink(long thinkStartMs) {
        return THINK_CLOSE + THINK_TIME_OPEN + (System.currentTimeMillis() - thinkStartMs) + THINK_TIME_CLOSE;
    }

    /**
     * 思考增量/累积语义自适应。Spring AI 2.0.1 起流式每个 chunk 的 reasoningContent
     * 元数据是"从头累积的全文"（OpenAiChatModel 按响应 id 在流生命周期内逐块拼接），
     * 1.x 时代是纯增量——按增量 append 累积值会 O(n²) 膨胀（2026-09-15 线上 30MB 事件事故）。
     * 第二块思考到达时探测一次：新块以上一块原文为前缀即判定累积语义，此后只取新增后缀；
     * 探测在前几块完成，前缀比较开销可忽略。增量语义下新块恰以上一块全文开头时会误判一次
     * （丢失少量重复字符），代价远小于累积语义漏判。
     *
     * @return 本次真正的思考增量（累积语义下与上一块相同则返回空串）
     */
    static String thinkDeltaOf(String reasoning, String[] lastRaw, boolean[] cumulative) {
        String last = lastRaw[0];
        if (cumulative[0]) {
            if (reasoning.length() >= last.length() && reasoning.startsWith(last)) {
                lastRaw[0] = reasoning;
                return reasoning.substring(last.length());
            }
            // 长度回退/前缀不符：语义已变，回退增量模式重新探测
            cumulative[0] = false;
        } else if (!last.isEmpty() && reasoning.length() > last.length() && reasoning.startsWith(last)) {
            cumulative[0] = true;
            lastRaw[0] = reasoning;
            return reasoning.substring(last.length());
        }
        lastRaw[0] = reasoning;
        return reasoning;
    }

    /**
     * 思考复读检测：① 短周期——同一 ≤40 字单元原文连续重复 ≥5 次（2026-09-26 线上：
     * "并行两个调用。并发两个调用。"式复读）；② 长周期——41~200 字整句循环，归一化
     * （去 &lt;br&gt;/空白/标点）后连续重复 ≥3 次（2026-10-05 线上：模型整句循环、句间夹
     * &lt;br&gt; 与杂散符号，原文匹配够不着，烧到 23739 字符才被模型 length 截断）。
     */
    static boolean reasoningLoopDetected(StringBuilder thinkBuf, int thinkStartLen) {
        int len = thinkBuf.length() - thinkStartLen;
        if (len < LOOP_MIN_PERIOD * 5 + 40) {
            return false;
        }
        String tail = thinkBuf.substring(Math.max(thinkStartLen, thinkBuf.length() - 240));
        int total = tail.length();
        for (int period = LOOP_MIN_PERIOD; period <= 40; period++) {
            if (total < period * 5) {
                break;
            }
            String unit = tail.substring(total - period);
            if (unit.trim().length() < LOOP_MIN_PERIOD) {
                continue;
            }
            boolean loop = true;
            for (int i = 2; i <= 5; i++) {
                if (!tail.regionMatches(total - period * i, unit, 0, period)) {
                    loop = false;
                    break;
                }
            }
            if (loop) {
                return true;
            }
        }
        // 长周期整句循环：分隔符会让原文周期不恒定，归一化后按 3 次连续重复判环（尾窗放大到 1300）
        String longTail = thinkBuf.substring(Math.max(thinkStartLen, thinkBuf.length() - 1300));
        String norm = longTail.replaceAll("<br\\s*/?>", "").replaceAll("[^\\p{L}\\p{N}]", "");
        int n = norm.length();
        if (n < 41 * 3) {
            return false;
        }
        for (int period = 41; period <= 200; period++) {
            if (n < period * 3) {
                break;
            }
            String unit = norm.substring(n - period);
            if (norm.regionMatches(n - period * 2, unit, 0, period)
                    && norm.regionMatches(n - period * 3, unit, 0, period)) {
                return true;
            }
        }
        return false;
    }

    /** 碎片化检测窗口（字符）：塌缩持续这么久后即可判定，代价是约一个窗口的延迟 */
    static final int FRAGMENT_WINDOW = 600;
    /** 判定所需最少句子数（防止正常长思考被误判） */
    static final int FRAGMENT_MIN_UNITS = 100;
    /** 句均长判定线：正常要点思考 ≥8 字符/句，塌缩后 ≤3 */
    static final double FRAGMENT_AVG_UNIT_LEN = 4.0;

    /**
     * 思考碎片化检测——同义转圈式复读的第二道网（2026-10-20 线上：模型在"并行发起两个
     * 工具调用"的决策点反复再规划，塌缩成"双发。开查。就绪。双轨。并行。…"式同义词轮转，
     * 每圈换词、单元互不相同，精确周期检测①②全部打不中，烧到本轮结束才停）。该形态逃不掉
     * "句子越写越碎"的特征：按句读切分近窗归一化后算句均长，句数足够多且均值骤降即判定。
     * 与精确周期检测同走"静默重试→再犯中止"通道。
     */
    static boolean reasoningFragmentationDetected(StringBuilder thinkBuf, int thinkStartLen) {
        int roundLen = thinkBuf.length() - thinkStartLen;
        if (roundLen < FRAGMENT_WINDOW) {
            return false;
        }
        String tail = thinkBuf.substring(thinkBuf.length() - FRAGMENT_WINDOW);
        int units = 0;
        int chars = 0;
        for (String unit : tail.split("[。！？!?；;，,、\\n\\r]")) {
            String norm = unit.replaceAll("<br\\s*/?>", "").replaceAll("[^\\p{L}\\p{N}]", "");
            if (norm.isEmpty()) {
                continue;
            }
            units++;
            chars += norm.length();
        }
        return units >= FRAGMENT_MIN_UNITS && chars < units * FRAGMENT_AVG_UNIT_LEN;
    }

    /** 追加并封顶：超出 {@link #MAX_BUFFER_CHARS} 的部分丢弃（每缓冲只 warn 一次） */
    private static void appendCapped(StringBuilder buf, String text, boolean[] truncated, String label) {
        if (buf.length() >= MAX_BUFFER_CHARS) {
            if (!truncated[0]) {
                truncated[0] = true;
                log.warn("流式{}超过 {} 字符上限，超出部分已截断", label, MAX_BUFFER_CHARS);
            }
            return;
        }
        buf.append(text, 0, Math.min(text.length(), MAX_BUFFER_CHARS - buf.length()));
    }

    /** 思考增量专用 partial 事件：不落会话，仅透传给订阅者（前端）实时渲染 */
    private static LlmResponse partialText(String text) {
        return LlmResponse.builder()
                .content(Content.builder().role("model")
                        .parts(java.util.List.of(Part.fromText(text))).build())
                .partial(true)
                .build();
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        throw new UnsupportedOperationException(
                "Live connection is not supported for Spring AI models.");
    }

    /** 提取思考内容：Spring AI 2.0 把 OpenAI 兼容网关的 reasoning_content 放在 AssistantMessage metadata（流式/非流式一致） */
    private static String reasoningOf(ChatResponse chatResponse) {
        try {
            if (chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null
                    || chatResponse.getResult().getOutput().getMetadata() == null) {
                return null;
            }
            Object reasoning = chatResponse.getResult().getOutput().getMetadata().get("reasoningContent");
            return reasoning instanceof String text ? text : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 提取正文增量（流式 chunk 的 delta 文本），异常与空值统一归空串 */
    private static String textOf(ChatResponse chatResponse) {
        try {
            if (chatResponse == null || chatResponse.getResult() == null
                    || chatResponse.getResult().getOutput() == null) {
                return "";
            }
            String text = chatResponse.getResult().getOutput().getText();
            return text == null ? "" : text;
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractModelName(Object model) {
        // Spring AI models may not always have a straightforward way to get model name
        // This is a fallback that can be overridden by providing explicit model name
        String className = model.getClass().getSimpleName();
        return className.toLowerCase().replace("chatmodel", "").replace("model", "");
    }

    private SpringAIProperties.Observability createDefaultObservabilityConfig() {
        SpringAIProperties.Observability config = new SpringAIProperties.Observability();
        config.setEnabled(true);
        config.setMetricsEnabled(true);
        config.setIncludeContent(false);
        return config;
    }

    /**
     * 从 ChatResponse 提取一轮模型调用的用量；无 usage 或全 0 返回 null
     * （网关不回 usage 时自动跳过记账）。cachedTokens 来自 Spring AI 2.0 原生的
     * cacheReadInputTokens（OpenAI 兼容网关的 prompt_tokens_details.cached_tokens），
     * 已计入 inputTokens——拆开记才能算清真实计费输入。
     */
    static TokenUsageVO tokenUsageOf(ChatResponse chatResponse) {
        try {
            if (chatResponse == null || chatResponse.getMetadata() == null) {
                return null;
            }
            return tokenUsageOf(chatResponse.getMetadata().getUsage());
        } catch (Exception e) {
            // 网关 usage 字段形态异常时跳过记账，不影响对话
            return null;
        }
    }

    /** 是否携带真实用量（区分网关不回 usage 时默认填的 EmptyUsage/全 0 占位） */
    private static boolean isMeaningfulUsage(Usage usage) {
        try {
            Integer total = usage.getTotalTokens();
            Integer prompt = usage.getPromptTokens();
            Integer completion = usage.getCompletionTokens();
            return (total != null && total > 0) || (prompt != null && prompt > 0)
                    || (completion != null && completion > 0);
        } catch (Exception e) {
            return false;
        }
    }

    /** Usage → 用量值对象；null/全 0 返回 null（跳过记账） */
    static TokenUsageVO tokenUsageOf(Usage usage) {
        try {
            if (usage == null) {
                return null;
            }
            long input = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            long output = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            long cached = usage.getCacheReadInputTokens() == null ? 0 : usage.getCacheReadInputTokens();
            long totalTokens = usage.getTotalTokens() == null || usage.getTotalTokens() <= 0
                    ? input + output : usage.getTotalTokens();
            if (input <= 0 && output <= 0) {
                return null;
            }
            return TokenUsageVO.builder()
                    .inputTokens(input).outputTokens(output).cachedTokens(cached)
                    .totalTokens(totalTokens).llmCalls(1).build();
        } catch (Exception e) {
            // 网关 usage 字段形态异常时跳过记账，不影响对话
            return null;
        }
    }

    private int extractTokenCount(ChatResponse chatResponse) {
        // Spring AI may include usage metadata in the response
        // This is a simplified implementation - actual token counts depend on provider
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractInputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getPromptTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private int extractOutputTokenCount(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getCompletionTokens();
            }
        } catch (Exception e) {
            // Ignore errors in token extraction
        }
        return 0;
    }

    private String extractTextFromResponse(LlmResponse response) {
        if (response.content().isPresent() && response.content().get().parts().isPresent()) {
            return response.content().get().parts().get().stream()
                    .map(part -> part.text().orElse(""))
                    .filter(text -> text != null && !text.isEmpty())
                    .findFirst()
                    .orElse("");
        }
        return "";
    }

}
