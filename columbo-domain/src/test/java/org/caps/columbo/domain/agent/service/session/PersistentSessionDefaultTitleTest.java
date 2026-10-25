package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import com.google.adk.sessions.Session;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 会话创建默认命名 "yyyy-MM-dd-0X"：当日序号递增、重命名过的不覆盖
 */
class PersistentSessionDefaultTitleTest {

    /** 内存版存储：只实现本测试路径用到的方法 */
    static class FakeStore implements ISessionStorePort {
        final Map<String, ChatSessionRecordVO.ChatSessionRecordVOBuilder> rows = new LinkedHashMap<>();

        private ChatSessionRecordVO row(String sessionId) {
            return rows.computeIfAbsent(sessionId,
                    k -> ChatSessionRecordVO.builder().sessionId(k).appName("app").title(null))
                    .build();
        }

        @Override
        public void saveSession(String appName, String userId, String sessionId) {
            row(sessionId);
        }

        @Override
        public void saveEvent(String appName, String userId, String sessionId, String eventId,
                              String author, String eventJson) {
        }

        @Override
        public List<String> loadEvents(String appName, String userId, String sessionId) {
            return List.of();
        }

        @Override
        public List<ChatSessionRecordVO> loadSessions(String userId) {
            List<ChatSessionRecordVO> list = new ArrayList<>();
            rows.forEach((k, b) -> list.add(b.build()));
            return list;
        }

        @Override
        public void deleteSession(String appName, String userId, String sessionId) {
            rows.remove(sessionId);
        }

        @Override
        public boolean renameSession(String appName, String userId, String sessionId, String title) {
            ChatSessionRecordVO current = row(sessionId);
            rows.put(sessionId, ChatSessionRecordVO.builder()
                    .sessionId(sessionId).appName("app").title(title));
            return current != null;
        }

        @Override
        public boolean moveSession(String appName, String userId, String sessionId, String space) {
            return true;
        }

        @Override
        public void recordTokenUsage(String appName, String userId, String sessionId,
                                     org.caps.columbo.domain.agent.model.valobj.TokenUsageVO delta) {
        }

        @Override
        public org.caps.columbo.domain.agent.model.valobj.TokenUsageVO loadTokenUsage(String appName, String sessionId) {
            return org.caps.columbo.domain.agent.model.valobj.TokenUsageVO.builder().build();
        }
    }

    @Test
    void createsSequentialDefaultTitlesPerDay() {
        FakeStore store = new FakeStore();
        PersistentSessionService service = new PersistentSessionService(store);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        Session first = service.createSession("app", "u1", null, null).blockingGet();
        Session second = service.createSession("app", "u1", null, null).blockingGet();

        assertEquals(today + "-01", titleOf(store, first.id()));
        assertEquals(today + "-02", titleOf(store, second.id()));
    }

    @Test
    void renamedSessionKeepsCustomTitle() {
        FakeStore store = new FakeStore();
        PersistentSessionService service = new PersistentSessionService(store);
        Session session = service.createSession("app", "u1", null, null).blockingGet();
        store.renameSession("app", "u1", session.id(), "值班台账");
        // 再次登记（幂等场景）不覆盖自定义标题
        service.createSession("app", "u1", null, session.id()).blockingGet();
        assertEquals("值班台账", titleOf(store, session.id()));
    }

    private static String titleOf(FakeStore store, String sessionId) {
        ChatSessionRecordVO record = store.loadSessions("u1").stream()
                .filter(r -> sessionId.equals(r.getSessionId()))
                .findFirst().orElse(null);
        assertNotNull(record);
        return record.getTitle();
    }
}
