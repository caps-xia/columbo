package org.caps.columbo.domain.agent.model.valobj.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * 处理器链配置：链名 → 处理器 Bean 名列表（按序执行）。
 * <pre>
 * ai:
 *   agent:
 *     pipeline:
 *       chain:
 *         chat: [sensitiveWordProcessor, rateLimitProcessor]   # 对话消息预加工链示例
 * </pre>
 * 提炼自 car-price-platform 的 pipeline 框架（配置化责任链）。
 *
 * @author caps
 */
@Data
@ConfigurationProperties(prefix = "ai.agent.pipeline", ignoreInvalidFields = true)
public class PipelineProperties {

    /** key：链名（业务自行约定，如 chat = 对话消息预加工链）；value：该链全部处理器的 Bean 名，按序执行 */
    private Map<String, List<String>> chain;
}
