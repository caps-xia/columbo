package org.caps.columbo.domain.agent.service.armory.matter.mcp.client.impl;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Slf4j
@Service
public class LocalToolMcpCreateService  implements TooMcpCreateService {

    @Resource
    protected ApplicationContext applicationContext;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ToolMcp toolMcp) {
        AiAgentConfigTableVO.Module.ToolMcp.LocalParameters local = toolMcp.getLocal();
        String name = local.getName();

        Object bean = applicationContext.containsBean(name) ? applicationContext.getBean(name) : null;
        if (!(bean instanceof ToolCallbackProvider provider)) {
            throw new IllegalArgumentException("local 工具 '" + name + "' 必须指向 ToolCallbackProvider Bean"
                    + "（在 Application 中以 @Bean 注册），当前" + (bean == null ? "未找到该 Bean" : "是: " + bean.getClass().getName()));
        }
        log.info("tool local mcp initialize {}", name);

        return provider.getToolCallbacks();
    }

}