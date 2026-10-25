package org.caps.columbo.api;

import org.caps.columbo.api.dto.*;
import org.caps.columbo.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * 智能体服务接口
 * @author caps 
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    Response<List<ChatSessionResponseDTO>> queryChatSessions(String userId);

    Response<List<ChatMessageResponseDTO>> queryChatMessages(String agentId, String userId, String sessionId);

    Response<Boolean> deleteSession(String agentId, String userId, String sessionId);

    /** 会话重命名（右击重命名，自定义标题覆盖默认标题） */
    Response<Boolean> renameSession(String agentId, String userId, String sessionId, String title);

    /** 会话移动到归档空间（拖拽归档；空串归位未归档） */
    Response<Boolean> moveSession(String agentId, String userId, String sessionId, String space);

    /** 各智能体可切换模型池与当前模型（chat-model.models 配置后支持动态切换） */
    Response<List<AgentModelResponseDTO>> queryAgentModels();

    /** 运行时切换模型：单 agent 重装配，会话保留；重启回到 yml 默认 */
    Response<Boolean> switchAgentModel(SwitchModelRequestDTO requestDTO);

    /** 会话累计 token 用量（输入/输出/缓存命中/调用次数）：成本记账查询入口 */
    Response<SessionTokenUsageResponseDTO> querySessionTokenUsage(String agentId, String userId, String sessionId);

}
