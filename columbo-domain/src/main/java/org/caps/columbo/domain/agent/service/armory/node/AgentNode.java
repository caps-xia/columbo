package org.caps.columbo.domain.agent.service.armory.node;

import com.google.adk.agents.LlmAgent;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.caps.columbo.domain.agent.model.entity.ArmoryCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.service.armory.AbstractArmorySupport;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.TooMcpCreateService;
import org.caps.columbo.domain.agent.service.armory.matter.mcp.client.factory.DefaultMcpClientFactory;
import org.caps.columbo.domain.agent.service.armory.matter.patch.MySpringAI;
import org.caps.columbo.domain.agent.service.armory.matter.patch.ParallelToolCallingManager;
import org.caps.columbo.domain.agent.service.armory.matter.skills.ToolSkillsCreateService;
import org.caps.columbo.domain.agent.service.armory.matter.tools.SpringAiToolCallbackTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AgentNode extends AbstractArmorySupport {

    @Resource
    private AgentWorkflowNode agentWorkflowNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    /** 同轮多工具并行执行度：模型并行打包的独立调用（多环境探量等）并发跑，
     *  墙钟求和变取最大；0/1 关闭（回退 Sanitizing 串行行为） */
    @org.springframework.beans.factory.annotation.Value("${ai.agent.tool-parallelism:4}")
    private int toolParallelism;

    /** Spring AI 每工具每轮调用上限（默认 40 太紧，并行轮次十几轮即撞穿）：
     *  放宽为可配值，超限 RETURN_ERROR_RESPONSE 回模型自换策略，主 governor 仍是 ToolLoopGuard */
    @org.springframework.beans.factory.annotation.Value("${ai.agent.tool-call-limit:200}")
    private int toolCallLimit;

    @Resource
    private org.caps.columbo.domain.agent.service.session.StreamingMailbox streamingMailbox;

    /** 会话 token 计量器：MySpringAI 每轮模型调用按会话累加用量（可空场景由构造器兜底） */
    @Resource
    private org.caps.columbo.domain.agent.service.session.SessionTokenMeter sessionTokenMeter;

    /** 流停滞超时（秒）：接受流式却长期不吐块的中断阈值，0/负数关闭 */
    @org.springframework.beans.factory.annotation.Value("${ai.agent.stream-stall-timeout-seconds:240}")
    private long streamStallTimeoutSeconds;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AgentNode");

        ChatModel chatModel = dynamicContext.getChatModel();

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        List<AiAgentConfigTableVO.Module.Agent> agents = aiAgentConfigTableVO.getModule().getAgents();

        for (AiAgentConfigTableVO.Module.Agent agentConfig : agents) {
            LlmAgent.Builder builder = LlmAgent.builder()
                    .name(agentConfig.getName())
                    .description(agentConfig.getDescription())
                    .model(new MySpringAI(chatModel, new ParallelToolCallingManager(toolParallelism, toolCallLimit), streamingMailbox,
                            sessionTokenMeter, streamStallTimeoutSeconds))
                    .instruction(agentConfig.getInstruction())
                    .outputKey(agentConfig.getOutputKey());

            // 工具挂在 Agent 级：读取 agent 条目下的 tool-mcp-list / tool-skills-list，
            // 工厂产出 Spring AI ToolCallback 后适配为 ADK 工具，随本 Agent 生效
            List<Object> adkTools = buildAgentTools(agentConfig);
            if (!adkTools.isEmpty()) {
                log.info("Agent [{}] 注册 {} 个工具", agentConfig.getName(), adkTools.size());
                builder.tools(adkTools);
            }

            dynamicContext.getAgentGroup().put(agentConfig.getName(), builder.build());
        }

        return router(requestParameter, dynamicContext);
    }

    private List<Object> buildAgentTools(AiAgentConfigTableVO.Module.Agent agentConfig) throws Exception {
        List<Object> adkTools = new ArrayList<>();

        List<AiAgentConfigTableVO.Module.ToolMcp> toolMcpList = agentConfig.getToolMcpList();
        if (toolMcpList != null && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ToolMcp toolMcp : toolMcpList) {
                TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                for (ToolCallback toolCallback : tooMcpCreateService.buildToolCallback(toolMcp)) {
                    adkTools.add(new SpringAiToolCallbackTool(toolCallback));
                }
            }
        }

        List<AiAgentConfigTableVO.Module.ToolSkills> toolSkillsList = agentConfig.getToolSkillsList();
        if (toolSkillsList != null && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ToolSkills toolSkills : toolSkillsList) {
                for (ToolCallback toolCallback : toolSkillsCreateService.buildToolCallback(toolSkills)) {
                    adkTools.add(new SpringAiToolCallbackTool(toolCallback));
                }
            }
        }

        return adkTools;
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentWorkflowNode;
    }

}
