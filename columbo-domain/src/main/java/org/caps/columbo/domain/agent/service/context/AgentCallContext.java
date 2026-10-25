package org.caps.columbo.domain.agent.service.context;

import java.util.function.Supplier;

/**
 * 工具执行期的「当前调用用户」上下文（ThreadLocal）。
 *
 * <p>解决的问题：ADK/Reactor 的流式工具循环运行在回调与池化线程上，触发层（HTTP 线程）
 * 的 ThreadLocal 带不过去；而工具侧（会话归属判定等）需要知道"这次调用
 * 属于哪个用户"，才能把事件路由到本人的 SSE 活跃流、把确认请求只弹给本人。
 *
 * <p>传递链路：MySpringAI 在执行工具前从运行句柄取 userId 绑定（串行路径同线程生效；
 * 并行路径由 ParallelToolCallingManager 在提交任务时捕获、worker 线程内恢复），
 * finally 里清除。未绑定时工具侧拿到的 current() 为 null，相关路由静默降级
 * （同步对话 / 单测等无流场景不推送）。
 *
 * @author caps
 */
public final class AgentCallContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AgentCallContext() {
    }

    /** 绑定当前线程的调用用户（null/空白忽略，绑定前无需判空） */
    public static void bind(String userId) {
        if (userId != null && !userId.isBlank()) {
            CURRENT.set(userId);
        }
    }

    /** 当前线程的调用用户；未绑定时返回 null */
    public static String current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 捕获-恢复型执行：在当前上下文之上临时换绑，结束后恢复原值（嵌套安全） */
    public static <T> T with(String userId, Supplier<T> action) {
        String previous = CURRENT.get();
        bind(userId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
