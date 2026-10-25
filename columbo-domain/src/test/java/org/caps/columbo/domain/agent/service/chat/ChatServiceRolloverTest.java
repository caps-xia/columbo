package org.caps.columbo.domain.agent.service.chat;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import org.caps.columbo.domain.agent.adapter.port.ISessionSummarizePort;
import org.caps.columbo.domain.agent.service.session.SessionCompactionService;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionCompactionProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强制翻篇测试（413 紧急压缩重试的地基）：新会话登记翻篇链、摘要结转、
 * 并发合并、会话服务故障上抛。BaseSessionService 用匿名实现做假件。
 */
class ChatServiceRolloverTest {

    /** 会话服务假件：createSession 发号 fresh-N，appendEvent 留痕 */
    private static final class FakeSessionService implements BaseSessionService {
        final AtomicInteger seq = new AtomicInteger();
        final List<Event> appended = new ArrayList<>();
        final boolean failCreate;

        FakeSessionService() {
            this.failCreate = false;
        }

        FakeSessionService(boolean failCreate) {
            this.failCreate = failCreate;
        }

        @Override
        public io.reactivex.rxjava3.core.Single<Session> createSession(
                String appName, String userId,
                java.util.concurrent.ConcurrentMap<String, Object> state, String sessionId) {
            if (failCreate) {
                return io.reactivex.rxjava3.core.Single.error(new IllegalStateException("库不可用"));
            }
            return io.reactivex.rxjava3.core.Single.just(
                    Session.builder("fresh-" + seq.incrementAndGet()).appName(appName).userId(userId).build());
        }

        @Override
        public io.reactivex.rxjava3.core.Maybe<Session> getSession(
                String appName, String userId, String sessionId,
                java.util.Optional<com.google.adk.sessions.GetSessionConfig> config) {
            // 返回带一条事件的旧会话（summarize 的 minEvents=1 可过；事件内容无断言）
            Event old = Event.builder().id("e1").author("user")
                    .content(com.google.genai.types.Content.fromParts(
                            com.google.genai.types.Part.fromText("QA 环境订单服务 500"))).build();
            return io.reactivex.rxjava3.core.Maybe.just(
                    Session.builder(sessionId).appName(appName).userId(userId)
                            .events(List.of(old)).build());
        }

        @Override
        public io.reactivex.rxjava3.core.Single<ListSessionsResponse> listSessions(String appName, String userId) {
            return io.reactivex.rxjava3.core.Single.error(new UnsupportedOperationException());
        }

        @Override
        public io.reactivex.rxjava3.core.Completable deleteSession(String appName, String userId, String sessionId) {
            return io.reactivex.rxjava3.core.Completable.complete();
        }

        @Override
        public io.reactivex.rxjava3.core.Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
            return io.reactivex.rxjava3.core.Single.error(new UnsupportedOperationException());
        }

        @Override
        public io.reactivex.rxjava3.core.Single<Event> appendEvent(Session session, Event event) {
            appended.add(event);
            return io.reactivex.rxjava3.core.Single.just(event);
        }
    }

    /** 摘要端口假件：返回固定摘要（null=模拟摘要失败降级） */
    private static ISessionSummarizePort summarizePort(String fixed) {
        return (systemPrompt, transcript) -> fixed;
    }

    private static SessionCompactionService compactionService(ISessionSummarizePort port) {
        SessionCompactionProperties properties = new SessionCompactionProperties();
        properties.setEnabled(true);
        properties.setMinEvents(1);
        properties.setBaseUrl("http://fake");
        properties.setModel("fake-model");
        return new SessionCompactionService(port, properties);
    }

    private ChatService newService(ISessionSummarizePort port) {
        ChatService service = new ChatService();
        service.sessionCompactionService = compactionService(port);
        return service;
    }

    @Test
    void 强制翻篇创建新会话并登记翻篇链() {
        ChatService service = newService(summarizePort("已定位到 QA 环境订单服务 500，初步怀疑超时配置。"));
        FakeSessionService sessions = new FakeSessionService();

        ChatService.ForcedRollover forced = service.forceRollover(sessions, "tars_100011", "caps", "old-1");

        assertEquals("fresh-1", forced.sessionId());
        assertTrue(forced.summarized(), "摘要成功应标记 summarized");
        assertEquals("fresh-1", service.sessionRollover.get("old-1"), "翻篇链应登记 old-1 -> fresh-1");
        assertEquals(1, sessions.appended.size(), "摘要应以 user 事件追加进新会话");
        String text = sessions.appended.get(0).stringifyContent();
        assertNotNull(text);
        assertTrue(text.contains("【前情摘要】"));
        assertTrue(text.contains("已定位到 QA 环境订单服务 500"));
    }

    @Test
    void 摘要失败降级普通翻篇() {
        ChatService service = newService(summarizePort(null));
        FakeSessionService sessions = new FakeSessionService();

        ChatService.ForcedRollover forced = service.forceRollover(sessions, "tars_100011", "caps", "old-1");

        assertEquals("fresh-1", forced.sessionId());
        assertFalse(forced.summarized(), "摘要失败应降级为普通翻篇");
        assertTrue(sessions.appended.isEmpty(), "无摘要不应追加结转事件");
        assertEquals("fresh-1", service.sessionRollover.get("old-1"));
    }

    @Test
    void 并发翻篇复用先到会话() {
        ChatService service = newService(summarizePort("x"));
        service.sessionRollover.put("old-1", "winner");
        FakeSessionService sessions = new FakeSessionService();

        ChatService.ForcedRollover forced = service.forceRollover(sessions, "tars_100011", "caps", "old-1");

        assertEquals("winner", forced.sessionId(), "后到者应复用先到会话");
        assertFalse(forced.summarized());
        assertTrue(sessions.appended.isEmpty(), "复用先到会话不应重复结转摘要");
    }

    @Test
    void 会话服务故障上抛() {
        ChatService service = newService(summarizePort("x"));
        FakeSessionService sessions = new FakeSessionService(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.forceRollover(sessions, "tars_100011", "caps", "old-1"));
        assertTrue(error.getMessage().contains("紧急翻篇失败"));
    }
}
