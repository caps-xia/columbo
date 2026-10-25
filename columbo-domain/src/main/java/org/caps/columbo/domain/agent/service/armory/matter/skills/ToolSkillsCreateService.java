package org.caps.columbo.domain.agent.service.armory.matter.skills;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 skills 构建服务
 *
 * @author caps 
 * 2026/2/6 08:03
 */
public interface ToolSkillsCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ToolSkills toolSkills) throws Exception;

}
