package org.caps.columbo.domain.agent.service;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * 装配接口
 *
 * @author caps 
 * 2025/12/17 08:13
 */
public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

    /**
     * 单表重装配：运行时切换模型等场景复用装配链（registerBean 覆盖原注册，
     * sessionService 为共享单例，重装配不丢历史会话；进行中的旧流由旧 Runner 对象继续处理完）。
     */
    void reArmAgent(AiAgentConfigTableVO table) throws Exception;

}
