package org.caps.columbo.config;

import org.caps.columbo.domain.agent.model.valobj.AiProviderSpec;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部模型供应商注册表（ai.agent.providers.<key>）：
 * 模型池用「供应商key/模型名」限定写法引用（如 anthropic/claude-sonnet-4），
 * 不带前缀的条目走 agent 内置 ai-api 网关。键 = 供应商标识，值见 {@link AiProviderSpec}。
 *
 * <p>独立配置类：ChatModelNode（装配链）依赖本 Bean，若声明在 AiAgentAutoConfig
 * （其依赖 armoryService）会形成装配环，单独拆开即无环。
 */
@Configuration
public class AiProviderRegistryConfig {

    @Bean(name = "aiProviderRegistry")
    @ConfigurationProperties("ai.agent.providers")
    public Map<String, AiProviderSpec> aiProviderRegistry() {
        return new LinkedHashMap<>();
    }
}
