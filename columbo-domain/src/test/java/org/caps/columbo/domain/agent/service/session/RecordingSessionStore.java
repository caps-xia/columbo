package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用会话存储 fake：记录 recordTokenUsage 的每次调用（其余 no-op）。
 *
 * @author caps
 */
public class RecordingSessionStore implements ISessionStorePort {

    /** 每次用量累加的入参快照 */
    public final List<TokenUsageVO> recordedUsage = new ArrayList<>();

    @Override
    public void saveSession(String appName, String userId, String sessionId) {
    }

    @Override
    public void saveEvent(String appName, String userId, String sessionId, String eventId, String author, String eventJson) {
    }

    @Override
    public List<String> loadEvents(String appName, String userId, String sessionId) {
        return List.of();
    }

    @Override
    public List<ChatSessionRecordVO> loadSessions(String userId) {
        return List.of();
    }

    @Override
    public void deleteSession(String appName, String userId, String sessionId) {
    }

    @Override
    public boolean renameSession(String appName, String userId, String sessionId, String title) {
        return true;
    }

    @Override
    public boolean moveSession(String appName, String userId, String sessionId, String space) {
        return true;
    }

    @Override
    public void recordTokenUsage(String appName, String userId, String sessionId, TokenUsageVO delta) {
        recordedUsage.add(delta);
    }

    @Override
    public TokenUsageVO loadTokenUsage(String appName, String sessionId) {
        return TokenUsageVO.builder().build();
    }
}
