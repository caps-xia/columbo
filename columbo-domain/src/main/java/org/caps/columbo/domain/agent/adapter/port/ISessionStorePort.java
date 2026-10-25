package org.caps.columbo.domain.agent.adapter.port;

import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;

import java.util.List;

/**
 * 会话持久化端口：实现走 MyBatis-Plus + MySQL（infrastructure）。
 * 写路径（save*）失败应记日志不抛出，不得阻断对话；
 * 读路径（load*）失败需显式抛出——宁可报错也不能静默丢上下文。
 *
 * @author caps
 */
public interface ISessionStorePort {

    /** 登记会话（幂等，重复创建同 id 只刷新时间） */
    void saveSession(String appName, String userId, String sessionId);

    /** 追加事件 JSON（按 event_id 幂等去重；partial 中间帧不应进来） */
    void saveEvent(String appName, String userId, String sessionId, String eventId, String author, String eventJson);

    /** 按落库顺序返回该会话的全部事件 JSON；无记录返回空列表 */
    List<String> loadEvents(String appName, String userId, String sessionId);

    /** 按用户列出全部会话（最后活跃倒序，含首条用户事件 JSON 用于生成标题） */
    List<ChatSessionRecordVO> loadSessions(String userId);

    /** 删除会话及其全部事件 */
    void deleteSession(String appName, String userId, String sessionId);

    /** 会话重命名（自定义标题覆盖默认标题）；返回是否实际更新到行 */
    boolean renameSession(String appName, String userId, String sessionId, String title);

    /** 会话移动到归档空间（space 传空串归位未归档）；返回是否实际更新到行 */
    boolean moveSession(String appName, String userId, String sessionId, String space);

    /** 累计会话 token 用量（列累加而非覆盖；写失败由调用方降级，不得阻断对话） */
    void recordTokenUsage(String appName, String userId, String sessionId, TokenUsageVO delta);

    /** 查询会话累计 token 用量；无记录返回全 0 值对象 */
    TokenUsageVO loadTokenUsage(String appName, String sessionId);
}
