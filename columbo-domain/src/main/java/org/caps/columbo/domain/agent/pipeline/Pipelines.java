package org.caps.columbo.domain.agent.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 管道门面：执行指定名称的处理器链。
 * 链未配置时静默跳过（返回 true，对主流程零侵入）；处理器异常直接上抛，由全局异常处理统一兜底。
 *
 * @author caps
 */
@Slf4j
@Component
public class Pipelines {

    @Resource
    private PipelineFactory pipelineFactory;

    /**
     * 执行处理器链。
     *
     * @param chain   链名（对应 ai.agent.pipeline.chain.* 的 key）
     * @param context 链上下文（处理器可修改，改动对后续节点可见）
     * @return true = 全部执行完或链未配置；false = 被某处理器主动中止
     */
    public boolean doExecute(String chain, PipelineContext context) {
        List<PipelineProcessor<PipelineContext>> processors = pipelineFactory.getPipeline(chain);
        if (processors == null) {
            log.debug("处理器链未配置，跳过: {}", chain);
            return true;
        }
        for (PipelineProcessor<PipelineContext> processor : processors) {
            long start = System.nanoTime();
            boolean next = processor.doProcess(context);
            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("处理器链 {} 节点 {} 执行完成，耗时 {}ms", chain, processor.getClass().getSimpleName(), costMs);
            if (!next) {
                log.info("处理器链 {} 在节点 {} 被中止", chain, processor.getClass().getSimpleName());
                return false;
            }
        }
        return true;
    }
}
