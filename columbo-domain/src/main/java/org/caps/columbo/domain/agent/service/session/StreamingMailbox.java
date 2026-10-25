package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.types.utils.SerialNoGenerator;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式对话插入信箱：对话进行中，用户的新指令在「下一个工具循环轮」开始前注入模型上下文
 * （ZCode 式插入——发现方向不对时立即调整，而不是等本轮跑完）。
 *
 * 机制：ChatService 建流时 beginRun（登记 appName/userId/运行会话 id，双键含翻篇前后 id）；
 * POST /chat_insert 调 insert（入队并立即持久化为 user 事件，刷新后不丢）；
 * MySpringAI 在工具执行完、下一轮模型调用前 drain，把插入内容作为 user 消息拼进对话。
 *
 * 线程约定：beginRun 在 HTTP 线程调用并记录 ThreadLocal，MySpringAI 首轮在同线程捕获句柄，
 * 后续轮用捕获的句柄；insert/drain 走 ConcurrentHashMap，任意线程安全。
 */
@Slf4j
@Component
public class StreamingMailbox {

    private final ISessionStorePort sessionStorePort;

    /** 运行中会话登记：前端 sessionId 与运行时 sessionId（翻篇后会变化）都注册指向同一句柄 */
    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();

    /** 首轮捕获用 ThreadLocal：beginRun 在 HTTP 线程设置，MySpringAI 同线程读取 */
    private final ThreadLocal<RunHandle> current = new ThreadLocal<>();

    public StreamingMailbox(ISessionStorePort sessionStorePort) {
        this.sessionStorePort = sessionStorePort;
    }

    /** 建流登记：keys=前端会话 id + 运行时会话 id（任一为空忽略） */
    public void beginRun(String appName, String userId, String... sessionIds) {
        RunHandle handle = new RunHandle(appName, userId, runtimeId(sessionIds));
        for (String key : sessionIds) {
            if (key != null && !key.isBlank()) {
                runs.put(key, handle);
            }
        }
        current.set(handle);
    }

    /** 流结束清理：移除登记键 + 清 ThreadLocal（若在同线程） */
    public void endRun(String... sessionIds) {
        for (String key : sessionIds) {
            if (key != null && !key.isBlank()) {
                runs.remove(key);
            }
        }
        current.remove();
    }

    /** 仅清 ThreadLocal（HTTP 线程在订阅完成后调用，防止线程池复用串号） */
    public void clearThread() {
        current.remove();
    }

    /** 当前线程捕获的运行句柄（MySpringAI 首轮调用；未挂接返回 null） */
    public RunHandle current() {
        return current.get();
    }

    /**
     * 插入消息到进行中的对话：入队 + 立即持久化为 user 事件（刷新历史不丢）。
     *
     * @return true=已注入进行中的对话；false=该会话当前没有进行中的流（调用方应转为排队）
     */
    public boolean insert(String sessionId, String message) {
        RunHandle handle = sessionId == null ? null : runs.get(sessionId);
        if (handle == null || handle.closed) {
            return false;
        }
        handle.pending.addLast(message);
        persistUserEvent(handle, message);
        log.info("插入消息进对话 session={} len={} 待消费={}", sessionId, message.length(), handle.pending.size());
        return true;
    }

    /**
     * 停止进行中的流式运行：仅置取消标志，由 MySpringAI 在下一个流增量/轮边界检查并收尾
     * （SSE dispose 经 FlowableCache 传不进模型流，主动停止只能走标志位）。
     *
     * @return true=已置标志；false=该会话当前没有进行中的流
     */
    public boolean cancel(String sessionId) {
        RunHandle handle = sessionId == null ? null : runs.get(sessionId);
        if (handle == null || handle.closed) {
            return false;
        }
        handle.cancelled = true;
        log.info("用户停止流式运行 session={} runtime={}", sessionId, handle.runtimeSessionId);
        return true;
    }

    /** 消费全部待注入消息（模型下一轮前调用；返回后队列为空） */
    public List<String> drain(RunHandle handle) {
        if (handle == null) {
            return List.of();
        }
        List<String> drained = new ArrayList<>();
        String msg;
        while ((msg = handle.pending.pollFirst()) != null) {
            drained.add(msg);
        }
        return drained;
    }

    private void persistUserEvent(RunHandle handle, String message) {
        try {
            String eventId = SerialNoGenerator.generate("E");
            Event event = Event.builder()
                    .id(eventId)
                    .author("user")
                    .content(Content.fromParts(Part.fromText(message)))
                    .build();
            sessionStorePort.saveSession(handle.appName(), handle.userId(), handle.runtimeSessionId());
            sessionStorePort.saveEvent(handle.appName(), handle.userId(), handle.runtimeSessionId(),
                    eventId, "user", EventJsonCodec.write(event));
        } catch (Exception e) {
            // 持久化失败不阻断注入（本轮上下文仍能看到），只记日志
            log.warn("插入消息持久化失败 session={}: {}", handle.runtimeSessionId, e.getMessage());
        }
    }

    private static String runtimeId(String... sessionIds) {
        for (String key : sessionIds) {
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return "";
    }

    /** 一次流式运行的句柄 */
    public static final class RunHandle {
        private final String appName;
        private final String userId;
        private final String runtimeSessionId;
        private final Deque<String> pending = new ArrayDeque<>();
        volatile boolean closed;
        /** 用户主动停止标志（volatile：Stop 接口线程置位，模型流订阅线程轮询） */
        volatile boolean cancelled;

        RunHandle(String appName, String userId, String runtimeSessionId) {
            this.appName = appName;
            this.userId = userId;
            this.runtimeSessionId = runtimeSessionId;
        }

        public boolean cancelled() {
            return cancelled;
        }

        String appName() {
            return appName;
        }

        /** 调用归属用户（MySpringAI 绑定工具执行上下文用） */
        public String userId() {
            return userId;
        }

        public String runtimeSessionId() {
            return runtimeSessionId;
        }
    }
}
