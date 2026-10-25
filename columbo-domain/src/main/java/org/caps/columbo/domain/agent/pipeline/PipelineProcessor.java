package org.caps.columbo.domain.agent.pipeline;

/**
 * 处理器链节点：实现类注册为 Spring Bean，链在 yml 中按 Bean 名顺序编排。
 *
 * @param <T> 本处理器接受的上下文类型
 * @author caps
 */
public interface PipelineProcessor<T extends PipelineContext> {

    /**
     * 处理当前节点。
     *
     * @param context 链上下文（可变，改动对后续节点可见）
     * @return false 中止后续节点（由链门面决定整体语义，如对话消息被拦截）
     */
    boolean doProcess(T context);
}
