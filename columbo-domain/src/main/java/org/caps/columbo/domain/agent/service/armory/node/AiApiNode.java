package org.caps.columbo.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.caps.columbo.domain.agent.model.entity.ArmoryCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.service.armory.AbstractArmorySupport;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // Spring AI 2.0 起 OpenAiApi 被移除，连接参数收敛进 ChatOptions；
        // 这里解析出 SDK 可用的 baseUrl（并入 completions-path 前缀）与 apiKey
        dynamicContext.setAiApiEndpoint(org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO.of(
                aiApiConfig.getBaseUrl(),
                aiApiConfig.getApiKey(),
                aiApiConfig.getCompletionsPath()));

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}
