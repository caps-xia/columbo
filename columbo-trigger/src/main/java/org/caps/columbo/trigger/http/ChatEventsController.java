package org.caps.columbo.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.caps.columbo.domain.agent.service.IChatService;
import org.caps.columbo.trigger.http.dto.ChatEventsRequestDTO;
import org.caps.columbo.trigger.http.support.ThinkMarkerStreamParser;
import com.google.adk.events.Event;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import io.reactivex.rxjava3.core.Flowable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.caps.columbo.trigger.filter.TraceIdFilter;
import io.reactivex.rxjava3.disposables.Disposable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 结构化 SSE 对话接口（Columbo 前端专用）：把 ADK 事件流翻译成带类型的前端事件——
 * session / state_update / think_delta / think_done / text_delta / tool_result / done / error。
 * 与老的 chat_stream（裸文本 + ⟦think⟧ 标记，脚手架演示页用）并存，互不影响。
 */
@Slf4j
@Tag(name = "智能体服务", description = "结构化 SSE 流式对话（Columbo 前端）")
@RestController
@RequestMapping("/api/v1/")
public class ChatEventsController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * SSE 异步超时：须大于 agent 单轮最坏耗时（工具循环守护上限 40 轮 × 单查 4-16s），
     * 设小了长排查会在中途被掐断；与 chat_stream 保持一致（本端点另有心跳帧防空闲断连）。
     */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    @Resource
    private IChatService chatService;

    /** 会话归属：鉴权开启时一律取登录工号（数据隔离） */
    @Resource
    private CurrentUser currentUser;

    /** 心跳：防止代理/浏览器空闲断连（SSE comment 帧，不影响前端事件解析） */
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /** emitter -> 心跳任务（完成时取消） */
    private final Map<SseEmitter, java.util.concurrent.ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        heartbeat.shutdownNow();
    }

    @Operation(summary = "结构化流式对话", description = "SSE 事件流：session→(state_update|think_delta|think_done|text_delta|tool_result)*→done")
    @PostMapping(value = "chat_events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatEvents(@Valid @RequestBody ChatEventsRequestDTO requestDTO) {
        boolean hasMessage = StringUtils.hasText(requestDTO.getMessage());
        boolean hasImages = requestDTO.getImages() != null && !requestDTO.getImages().isEmpty();
        if (!hasMessage && !hasImages) {
            throw new org.caps.columbo.types.exception.AppException(
                    org.caps.columbo.types.enums.ResponseCode.ILLEGAL_PARAMETER.getCode(), "message 与 images 至少传一个");
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        // 订阅回调运行在其它线程，MDC（ThreadLocal）带不过去，显式携带便于排障
        String traceId = StringUtils.hasText(MDC.get(TraceIdFilter.TRACE_ID))
                ? MDC.get(TraceIdFilter.TRACE_ID) : String.valueOf(System.nanoTime());

        // 归属统一走 CurrentUser：鉴权开启时取登录工号，避免前端传参越权写他人会话
        String userId = currentUser.resolve(requestDTO.getUserId());

        // 会话：没传就新建，第一时间回传 session 事件（前端据此落历史记录）
        String sessionId = StringUtils.hasText(requestDTO.getSessionId())
                ? requestDTO.getSessionId()
                : chatService.createSession(requestDTO.getAgentId(), userId);
        send(emitter, "session", Map.of(
                "sessionId", sessionId,
                "agentId", requestDTO.getAgentId()));

        startHeartbeat(emitter);
        AtomicReference<Disposable> subscription = new AtomicReference<>();
        Runnable cleanup = () -> {
            stopHeartbeat(emitter);
            Disposable disposable = subscription.get();
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        try {
            Flowable<Event> events = chatService.handleMessageStream(
                    requestDTO.getAgentId(), userId, sessionId,
                    hasMessage ? requestDTO.getMessage() : "（请结合附图内容处理）",
                    hasImages ? requestDTO.getImages() : null);

            ThinkMarkerStreamParser parser = new ThinkMarkerStreamParser();
            // 正文增量去重账本：partial 文本事件实时转发并计数；final 事件（落会话用全量）
            // 只补未转发过的尾部，防 SSE 重复。事件作者变化（工作流阶段切换）即重置——
            // 新阶段的 final 没有对应增量时必须全量转发
            int[] textForwarded = {0};
            String[] lastAuthor = {""};

            Disposable disposable = events.subscribe(
                event -> handleEvent(emitter, parser, event, textForwarded, lastAuthor),
                    error -> {
                        log.error("chat_events 流式异常 traceId:{}", traceId, error);
                        stopHeartbeat(emitter);
                        send(emitter, "error", Map.of("message", safeMessage(error)));
                        emitter.complete();
                    },
                    () -> {
                        parser.flush(segment -> consumeSegment(emitter, segment, null, textForwarded));
                        stopHeartbeat(emitter);
                        send(emitter, "done", Map.of("sessionId", sessionId));
                        emitter.complete();
                    });
            subscription.set(disposable);
        } catch (Exception e) {
            // 同步阶段失败（智能体不存在/责任链拦截等）：回 SSE error 事件并收尾，
            // 避免异常进入全局处理器返回 JSON 破坏 SSE 协议
            log.error("chat_events 建流失败 traceId:{}", traceId, e);
            cleanup.run();
            send(emitter, "error", Map.of("message", safeMessage(e)));
            emitter.complete();
        }
        return emitter;
    }

    /** ADK 事件分流：工具调用 → state_update；工具响应 → tool_result；文本 → 思考/正文增量 */
    private void handleEvent(SseEmitter emitter, ThinkMarkerStreamParser parser, Event event,
                             int[] textForwarded, String[] lastAuthor) {
        try {
            // 阶段边界：事件作者变化（取证→成文等）时重置正文转发账本。只认非空 author——
            // 空作者事件（系统/工具事件等）若参与比较，会在阶段中途把账本清零，
            // 该阶段 final 的全量文本会重复转发（前端正文重复）
            String author = event.author() == null ? "" : event.author();
            if (!author.isEmpty() && !author.equals(lastAuthor[0])) {
                lastAuthor[0] = author;
                textForwarded[0] = 0;
            }
            if (!event.functionCalls().isEmpty()) {
                for (FunctionCall call : event.functionCalls()) {
                    String name = call.name().orElse("unknown");
                    String detail = call.args().map(args -> {
                        try {
                            String json = MAPPER.writeValueAsString(args);
                            return json.length() > 200 ? json.substring(0, 200) + "…" : json;
                        } catch (Exception e) {
                            return "";
                        }
                    }).orElse("");
                    send(emitter, "state_update", Map.of(
                            "phase", "tool",
                            "step", "调用工具 " + name,
                            "detail", detail));
                }
                return;
            }
            if (!event.functionResponses().isEmpty()) {
                for (FunctionResponse response : event.functionResponses()) {
                    handleToolResponse(emitter, response);
                }
                return;
            }
            // 用户回显事件跳过（前端本地已渲染）
            if ("user".equals(event.author())) {
                return;
            }
            String text = textToFeed(event.stringifyContent(), event.partial().orElse(false), textForwarded);
            if (!text.isEmpty()) {
                parser.feed(text, segment -> consumeSegment(emitter, segment, author, textForwarded));
            }
        } catch (Exception e) {
            log.warn("chat_events 事件处理失败（跳过该事件）: {}", e.getMessage());
        }
    }

    /**
     * 文本事件预处理 → 喂给流式解析器的内容（空串=不喂）：final 事件剥思考标记并按账本去重。
     * 注意空白增量（逐 token 流式下单独的 "\n"/" " chunk 很常见）必须原样放行，只按空串拦截——
     * 丢空白会让前端直播文本缺换行/缺空格（表格解析失效、数字粘连），与落库全量不一致，
     * 表现为"流式时 markdown 乱、刷新后正常"。
     */
    static String textToFeed(String text, boolean partial, int[] textForwarded) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (partial) {
            return text;
        }
        // 最终事件携带思考标记（为历史落库）：思考已通过 partial 增量推送过，
        // 这里剥掉标记，正文按下述账本去重后再喂给解析器
        text = text.replaceAll("⟦think⟧[\\s\\S]*?⟦/think⟧⟦thinktime⟧\\d+[^⟦]*⟦/thinktime⟧", "");
        if (textForwarded[0] == 0) {
            // 无增量转发过（非流式兜底等）：维持旧行为整段转发（trim 美化）
            return text.trim();
        }
        // 正文已按增量实时转发：只补未转发的尾部（流中止补尾/系统提示），
        // 不 trim——保持与增量拼接的长度严格对齐，防错位
        return dedupeForwardedText(text, textForwarded[0]);
    }

    /** 思考/耗时/正文片段 → 前端事件（author 供前端区分工作流阶段，可为 null；正文增量计数） */
    private void consumeSegment(SseEmitter emitter, ThinkMarkerStreamParser.Segment segment, String author,
                                int[] textForwarded) {
        switch (segment.type()) {
            case THINK -> {
                java.util.Map<String, Object> payload = new java.util.HashMap<>();
                payload.put("content", segment.content());
                if (author != null && !author.isBlank()) {
                    payload.put("author", author);
                }
                send(emitter, "think_delta", payload);
            }
            case THINK_TIME -> {
                Long costMs = ThinkMarkerStreamParser.parseCostMs(segment.content());
                send(emitter, "think_done", costMs != null
                        ? Map.of("costMs", costMs)
                        : Map.of("costMs", 0));
            }
            default -> {
                send(emitter, "text_delta", Map.of("content", segment.content()));
                textForwarded[0] += segment.content().length();
            }
        }
    }

    /**
     * final 全量文本扣掉已按增量转发的前缀，只留未转发尾部：
     * 正文已实时流式（MySpringAI partial）而 final 事件仍带全量（为落会话），
     * 直接转发会整段重复。账本与增量严格同长（final 不 trim），异常短于账本时
     * clamp 为空串（不倒灌、不越界）。
     */
    static String dedupeForwardedText(String fullText, int alreadyForwarded) {
        if (alreadyForwarded <= 0 || fullText == null) {
            return fullText;
        }
        int seen = Math.min(alreadyForwarded, fullText.length());
        return fullText.substring(seen);
    }

    /** 工具响应：回传执行摘要（tool/success/summary） */
    private void handleToolResponse(SseEmitter emitter, FunctionResponse response) {
        String name = response.name().orElse("unknown");
        Map<String, Object> body = response.response().orElse(null);

        boolean success = body == null || !Boolean.FALSE.equals(body.get("success"));
        String summary = body == null ? ""
                : (body.get("summary") instanceof String s && StringUtils.hasText(s)) ? s
                : (body.get("errorMsg") instanceof String e && StringUtils.hasText(e)) ? e : "";
        send(emitter, "tool_result", Map.of(
                "tool", name,
                "success", success,
                "summary", summary));
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(MAPPER.writeValueAsString(payload)));
        } catch (Exception e) {
            log.debug("SSE 发送失败（客户端可能已断开）: {} - {}", eventName, e.getMessage());
        }
    }

    private void startHeartbeat(SseEmitter emitter) {
        heartbeats.put(emitter, heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                stopHeartbeat(emitter);
            }
        }, 15, 15, TimeUnit.SECONDS));
    }

    private void stopHeartbeat(SseEmitter emitter) {
        java.util.concurrent.ScheduledFuture<?> future = heartbeats.remove(emitter);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return StringUtils.hasText(message) ? message : "对话服务异常，请稍后重试";
    }
}
