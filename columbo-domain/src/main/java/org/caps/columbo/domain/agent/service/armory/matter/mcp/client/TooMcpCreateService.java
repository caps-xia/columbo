package org.caps.columbo.domain.agent.service.armory.matter.mcp.client;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具 MCP 构建服务
 *
 * @author caps 
 * 2026/1/2 09:31
 */
public interface TooMcpCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ToolMcp toolMcp) throws Exception;

}
