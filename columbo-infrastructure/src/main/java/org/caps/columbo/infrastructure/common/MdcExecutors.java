package org.caps.columbo.infrastructure.common;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC 上下文透传：把提交任务时的 MDC（traceId 等）复制到执行线程，执行完恢复执行前快照。
 *
 * <p>异步/线程池场景下日志链路不丢的关键小件，配合 {@link ExecutorConfig} 使用。
 * 结束时是"恢复"而非无条件 clear——CallerRunsPolicy 等同线程执行场景下，
 * clear 会误清调用者自身的 traceId。
 */
public final class MdcExecutors {

    private MdcExecutors() {
    }

    /**
     * Spring 线程池（ThreadPoolTaskExecutor）用的 TaskDecorator：提交时快照 MDC，
     * 执行线程内还原，结束后恢复执行前快照。
     */
    public static TaskDecorator taskDecorator() {
        return runnable -> {
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous != null) {
                        MDC.setContextMap(previous);
                    } else {
                        MDC.clear();
                    }
                }
            };
        };
    }
}
