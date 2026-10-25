package org.caps.columbo.infrastructure.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 业务线程池装配：bizExecutor 供编程式异步（CompletableFuture / 启动预热）使用，
 * 经 {@link MdcExecutors#taskDecorator()} 透传 MDC（traceId）。
 * 用 ThreadPoolTaskExecutor 而非裸 ThreadPoolExecutor，生命周期交给 Spring 管理（优雅停机）。
 */
@Slf4j
@Configuration
public class ExecutorConfig {

    /**
     * 业务线程池。CallerRunsPolicy 背压——队列满时由提交线程执行，避免任务丢弃。
     */
    @Bean("bizExecutor")
    public ThreadPoolTaskExecutor bizExecutor() {
        int nThreads = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(nThreads);
        executor.setMaxPoolSize(nThreads * 5);
        executor.setKeepAliveSeconds(120);
        executor.setQueueCapacity(nThreads * 5 * 50);
        executor.setThreadNamePrefix("biz-pool-");
        executor.setTaskDecorator(MdcExecutors.taskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
