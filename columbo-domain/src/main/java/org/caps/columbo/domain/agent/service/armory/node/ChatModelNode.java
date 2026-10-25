package org.caps.columbo.domain.agent.service.armory.node;

import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import org.caps.columbo.domain.agent.model.entity.ArmoryCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.model.valobj.AiProviderSpec;
import org.caps.columbo.domain.agent.service.armory.AbstractArmorySupport;
import org.caps.columbo.domain.agent.service.armory.AiModelResolver;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.caps.columbo.domain.agent.service.armory.matter.patch.SanitizingToolCallingManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    /** 网关兼容补丁（迁自 trace-bot）：剥掉 toolCall arguments 的 {} 前缀污染、修复 content null 422 */
    @Resource
    private SanitizingToolCallingManager sanitizingToolCallingManager;

    /** 外部供应商注册表（ai.agent.providers.<key>，见 AiAgentAutoConfig 的 Bean 绑定），可为空 */
    @Resource(name = "aiProviderRegistry")
    private Map<String, AiProviderSpec> aiProviderRegistry;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - ChatModelNode");

        // 获取配置对象
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();

        // 解析当前模型：支持「供应商key/模型名」引用外部供应商，否则走 agent 内置 ai-api 网关
        AiProviderSpec builtin = AiModelResolver.builtinProvider(
                aiApiConfig.getBaseUrl(), aiApiConfig.getApiKey(), aiApiConfig.getCompletionsPath());
        AiModelResolver.ResolvedModel resolved = AiModelResolver.resolve(
                chatModelConfig.getModel(),
                aiProviderRegistry == null ? Map.of() : aiProviderRegistry,
                builtin);
        log.info("模型解析: {} -> 供应商[{}] 模型[{}]", chatModelConfig.getModel(),
                resolved.builtin() ? "内置ai-api" : "外部", resolved.modelId());

        // 按供应商协议构建对话模型（工具已下沉到 Agent 级，由 AgentNode 读取 agent 配置挂到 LlmAgent.tools）
        ChatModel chatModel = switch (AiModelResolver.normalizeFormat(resolved.provider())) {
            case ANTHROPIC -> buildAnthropicModel(resolved);
            case OPENAI -> buildOpenAiModel(resolved, sanitizingToolCallingManager);
        };

        // 故障切换（提炼自 waliapi Dispatcher）：models 池内其余模型依次作为备用，
        // 网关 5xx/429/超时自动切换；4xx 参数与鉴权类错误立即上抛
        chatModel = withFailover(chatModel, aiAgentConfigTableVO, aiApiConfig);

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    /** OpenAI 兼容协议：连接参数收敛进 ChatOptions，由底层 openai-java SDK 自建 okhttp 客户端 */
    private ChatModel buildOpenAiModel(AiModelResolver.ResolvedModel resolved,
                                       ToolCallingManager toolCallingManager) {
        org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO endpoint = org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO.of(
                resolved.provider().getBaseUrl(), resolved.provider().getApiKey(), resolved.provider().getCompletionsPath());
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .model(resolved.modelId())
                        .baseUrl(endpoint.baseUrl())
                        .apiKey(endpoint.apiKey())
                        // 网关挂起时避免请求线程无限阻塞；流式长任务放宽到 5 分钟
                        .timeout(Duration.ofSeconds(300))
                        .maxRetries(2)
                        .build())
                .toolCallingManager(toolCallingManager)
                .build();
    }

    /**
     * 模型故障切换装配：主模型 + models 池内其余模型（最多 3 个备用，按配置顺序）。
     * 备用模型构建失败只告警跳过（某个池内模型引用的外部供应商未配置时不应阻断装配）。
     */
    private ChatModel withFailover(ChatModel primary,
                                   AiAgentConfigTableVO aiAgentConfigTableVO,
                                   AiAgentConfigTableVO.Module.AiApi aiApiConfig) {
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<String> pool = chatModelConfig.getModels() == null ? List.of() : chatModelConfig.getModels();
        AiProviderSpec.AiApiFormat primaryFormat = AiModelResolver.normalizeFormat(
                AiModelResolver.resolve(chatModelConfig.getModel(),
                        aiProviderRegistry == null ? Map.of() : aiProviderRegistry,
                        AiModelResolver.builtinProvider(aiApiConfig.getBaseUrl(), aiApiConfig.getApiKey(), aiApiConfig.getCompletionsPath())).provider());
        List<ChatModel> delegates = new java.util.ArrayList<>();
        delegates.add(primary);
        for (String modelName : pool) {
            if (modelName == null || modelName.isBlank() || modelName.equals(chatModelConfig.getModel())) {
                continue;
            }
            if (delegates.size() >= 4) {
                break;
            }
            try {
                AiModelResolver.ResolvedModel resolved = AiModelResolver.resolve(
                        modelName,
                        aiProviderRegistry == null ? Map.of() : aiProviderRegistry,
                        AiModelResolver.builtinProvider(aiApiConfig.getBaseUrl(), aiApiConfig.getApiKey(), aiApiConfig.getCompletionsPath()));
                // 备用模型必须与主模型同协议族：FailoverChatModel 的 options 委托主模型，
                // 混协议池（如主 OpenAI + 备 Anthropic）会在模型侧 options 强转时 ClassCastException
                if (AiModelResolver.normalizeFormat(resolved.provider()) != primaryFormat) {
                    log.warn("备用模型 {} 协议族与主模型不同，跳过（故障切换仅支持同协议族）", modelName);
                    continue;
                }
                delegates.add(switch (primaryFormat) {
                    case ANTHROPIC -> buildAnthropicModel(resolved);
                    case OPENAI -> buildOpenAiModel(resolved, sanitizingToolCallingManager);
                });
            } catch (Exception e) {
                log.warn("备用模型构建失败 model={} err={}", modelName, e.getMessage());
            }
        }
        if (delegates.size() == 1) {
            return primary;
        }
        log.info("模型故障切换装配完成：主模型 + {} 个备用", delegates.size() - 1);
        return new org.caps.columbo.domain.agent.service.armory.matter.patch.FailoverChatModel(delegates);
    }

    /** Anthropic Messages 协议：官方 anthropic-java SDK，baseUrl 可指向兼容网关（如 GLM 的 /api/anthropic） */
    private ChatModel buildAnthropicModel(AiModelResolver.ResolvedModel resolved) {
        return AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder()
                        .model(resolved.modelId())
                        .baseUrl(resolved.provider().getBaseUrl())
                        .apiKey(resolved.provider().getApiKey())
                        .timeout(Duration.ofSeconds(300))
                        .maxRetries(2)
                        // Anthropic Messages 协议 max_tokens 必填，未配置时给安全默认（可按需调大）
                        .maxTokens(8192)
                        .build())
                .build();
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
