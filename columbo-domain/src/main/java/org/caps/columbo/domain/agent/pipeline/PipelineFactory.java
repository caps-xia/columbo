package org.caps.columbo.domain.agent.pipeline;

import org.caps.columbo.domain.agent.model.valobj.properties.PipelineProperties;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 管道工厂：按链名从配置取处理器 Bean 名列表，解析为处理器实例。
 *
 * @author caps
 */
@Component
public class PipelineFactory implements ApplicationContextAware {

    @Resource
    private PipelineProperties properties;

    private ApplicationContext context;

    /**
     * 根据链名获取处理器列表
     *
     * @return 链未配置时返回 null（调用方自行决定跳过或报错）；配置了但 Bean 不存在直接抛错（启动期问题应尽早暴露）
     */
    @SuppressWarnings("unchecked")
    public List<PipelineProcessor<PipelineContext>> getPipeline(String chain) {
        List<String> names = properties.getChain() == null ? null : properties.getChain().get(chain);
        if (names == null || names.isEmpty()) {
            return null;
        }
        List<PipelineProcessor<PipelineContext>> processors = new ArrayList<>(names.size());
        for (String name : names) {
            Object bean;
            try {
                bean = context.getBean(name);
            } catch (BeansException e) {
                throw new IllegalStateException("处理器链 \"" + chain + "\" 引用的 Bean 不存在: " + name, e);
            }
            if (!(bean instanceof PipelineProcessor)) {
                throw new IllegalStateException("处理器链 \"" + chain + "\" 引用的 Bean 未实现 PipelineProcessor: " + name);
            }
            processors.add((PipelineProcessor<PipelineContext>) bean);
        }
        return processors;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.context = applicationContext;
    }
}
