package org.caps.columbo.domain.agent.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 流式会话应用服务：SSE 心跳与收尾的领域编排（chat_stream 与 chat_events 共用）。
 * <p>
 * 为什么在应用层而不是 Controller：心跳调度、收尾回调注册的先后顺序（必须先注册再建流，
 * 否则建流同步异常会泄漏周期任务）属于「流式会话怎么稳定运行」的应用规则，与 HTTP 协议无关。
 * 心跳发送失败静默——连接死亡由 onCompletion/onError 收尾。
 *
 * @author caps
 */
@Slf4j
@Service
public class StreamingSessionAppService {

    /** 心跳间隔：LLM 思考/工具执行期间流可能静默数分钟，注释帧防代理/NAT 空闲断连 */
    private static final long HEARTBEAT_INTERVAL_MS = 15 * 1000L;

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 启动心跳并返回收尾句柄。
     *
     * @param sendHeartbeat 心跳发送动作（Controller 侧闭包：emitter.send(":ping")；发送失败静默）
     * @param onEnd         流收尾动作（取消上游订阅等）；调用方须在建流前注册好 emitter 回调，
     *                      保证任何退出路径都会执行
     * @return 句柄：end() 幂等（取消心跳 + 执行收尾）
     */
    public HeartbeatHandle startHeartbeat(Runnable sendHeartbeat, Runnable onEnd) {
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                sendHeartbeat.run();
            } catch (Exception ignored) {
                // 连接已断：由 onCompletion/onError 收尾，心跳本身静默
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        return new HeartbeatHandle(future, onEnd);
    }

    /** 心跳句柄：end() 幂等，重复调用无副作用 */
    public static final class HeartbeatHandle {
        private final ScheduledFuture<?> future;
        private final Runnable onEnd;
        private boolean ended;

        HeartbeatHandle(ScheduledFuture<?> future, Runnable onEnd) {
            this.future = future;
            this.onEnd = onEnd;
        }

        public void end() {
            if (ended) {
                return;
            }
            ended = true;
            future.cancel(false);
            onEnd.run();
        }
    }

    @PreDestroy
    public void shutdown() {
        heartbeatExecutor.shutdownNow();
    }
}
