package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 会话 token 计量器：把每次模型调用的用量按会话累加落库（成本记账）。
 *
 * <p>归属链路有两条：
 * <ul>
 *   <li>流式路径——MySpringAI 在首轮捕获 {@link StreamingMailbox.RunHandle}（含 appName/userId/
 *       运行会话 id），每轮模型调用结束时按句柄归属记录（insert）；</li>
 *   <li>同步路径——ChatService 在当前线程 {@link #bind} 归属范围，MySpringAI 非流式调用
 *       在同线程 {@link #recordCurrent} 读取（阻塞调用不换线程）。</li>
 * </ul>
 * 落库失败只告警不阻断对话（用量是旁路数据，丢一次计量不影响排查）。
 *
 * @author caps
 */
@Slf4j
@Component
public class SessionTokenMeter {

    private final ISessionStorePort store;

    /** 同步路径的归属范围：ChatService 在当前线程绑定，MySpringAI 同线程读取 */
    private final ThreadLocal<Scope> scope = new ThreadLocal<>();

    public record Scope(String appName, String userId, String sessionId) {
    }

    public SessionTokenMeter(ISessionStorePort store) {
        this.store = store;
    }

    /** 绑定当前线程的归属范围（同步对话路径进入前调用，finally 里 unbind） */
    public void bind(String appName, String userId, String sessionId) {
        scope.set(new Scope(appName, userId, sessionId));
    }

    public void unbind() {
        scope.remove();
    }

    public Scope current() {
        return scope.get();
    }

    /** 流式路径：按运行句柄归属记录一轮模型调用的用量 */
    public void record(StreamingMailbox.RunHandle run, String model, TokenUsageVO usage) {
        if (run == null) {
            return;
        }
        record(new Scope(run.appName(), run.userId(), run.runtimeSessionId()), model, usage);
    }

    /** 同步路径：按当前线程绑定的归属范围记录（未绑定时静默跳过，如单测/无归属调用） */
    public void recordCurrent(String model, TokenUsageVO usage) {
        record(current(), model, usage);
    }

    public void record(Scope target, String model, TokenUsageVO usage) {
        if (target == null || usage == null || usage.isEmpty()) {
            return;
        }
        try {
            store.recordTokenUsage(target.appName(), target.userId(), target.sessionId(), usage);
            log.debug("token 用量已累计 session={} model={} in={}（缓存{}）out={} calls={}",
                    target.sessionId(), model, usage.getInputTokens(), usage.getCachedTokens(),
                    usage.getOutputTokens(), usage.getLlmCalls());
        } catch (Exception e) {
            log.warn("token 用量记录失败（对话不受影响）session={}: {}", target.sessionId(), e.getMessage());
        }
    }
}
