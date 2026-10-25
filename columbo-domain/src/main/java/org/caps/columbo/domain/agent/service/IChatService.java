package org.caps.columbo.domain.agent.service;

import org.caps.columbo.domain.agent.model.entity.ChatCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.ChatMessageVO;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionVO;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 * @author caps 
 * 2025/12/17 08:13
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /** 带附图的消息：先用多模态模型识别图片提取文字，再进入对话（troubleshoot.image-understanding） */
    List<String> handleMessage(String agentId, String userId, String sessionId, String message, List<String> images);

    /** 带附图的流式消息：识别图片后进入对话 */
    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, List<String> images);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

    /** 按用户列出全部历史会话（最后活跃倒序，标题取首条用户消息；持久化关闭时返回空） */
    List<ChatSessionVO> queryChatSessions(String userId);

    /** 会话内消息列表：连续模型事件（思考/耗时/正文）合并为一条，与流式形态一致 */
    List<ChatMessageVO> queryChatMessages(String agentId, String userId, String sessionId);

    /** 删除会话（内存 + 持久层一起删） */
    void deleteChatSession(String agentId, String userId, String sessionId);

    /** 会话重命名（自定义标题，1-100 字） */
    void renameChatSession(String agentId, String userId, String sessionId, String title);

    /** 会话移动到归档空间（空串归位未归档） */
    void moveChatSession(String agentId, String userId, String sessionId, String space);

    /** 各智能体的可切换模型池与当前模型（chat-model.models 未配置时池 = [model]） */
    List<org.caps.columbo.domain.agent.model.valobj.AgentModelVO> queryAgentModels();

    /**
     * 运行时切换智能体模型：校验模型在池内 → 改装配源 → 单表重装配（会话保留）。
     * 内存态，重启回到 yml 默认模型。
     */
    void switchAgentModel(String agentId, String model);

    /** 会话累计 token 用量（输入/输出/缓存命中/调用次数）：成本记账查询入口 */
    TokenUsageVO querySessionTokenUsage(String agentId, String userId, String sessionId);

}
