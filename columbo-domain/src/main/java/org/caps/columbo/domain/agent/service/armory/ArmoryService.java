package org.caps.columbo.domain.agent.service.armory;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.caps.columbo.domain.agent.model.entity.ArmoryCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.service.IArmoryService;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Service
public class ArmoryService implements IArmoryService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Override
    public void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception {
        for (AiAgentConfigTableVO table : tables) {
            reArmAgent(table);
        }
    }

    @Override
    public void reArmAgent(AiAgentConfigTableVO table) throws Exception {
        StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> handler = defaultArmoryFactory.armoryStrategyHandler();
        handler.apply(
                ArmoryCommandEntity.builder()
                        .aiAgentConfigTableVO(table)
                        .build(),
                new DefaultArmoryFactory.DynamicContext());
    }

}
