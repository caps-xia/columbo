package org.caps.columbo.domain.agent.service.armory.matter.mcp.client.factory;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.impl.LocalToolMcpCreateService;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.impl.SSEToolMcpCreateService;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.impl.StdioToolMcpCreateService;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

@Slf4j
@Service
public class DefaultMcpClientFactory {

    @Resource
    private LocalToolMcpCreateService localToolMcpCreateService;

    @Resource
    private SSEToolMcpCreateService sseToolMcpCreateService;

    @Resource
    private StdioToolMcpCreateService stdioToolMcpCreateService;

    public TooMcpCreateService getTooMcpCreateService(AiAgentConfigTableVO.Module.ToolMcp toolMcp) {
        if (null != toolMcp.getLocal()) return localToolMcpCreateService;
        if (null != toolMcp.getSse()) return sseToolMcpCreateService;
        if (null != toolMcp.getStdio()) return stdioToolMcpCreateService;
        throw new AppException(ResponseCode.NOT_FOUND_METHOD);
    }

}
