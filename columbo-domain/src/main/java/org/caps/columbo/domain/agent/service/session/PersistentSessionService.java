package org.caps.columbo.domain.agent.service.session;

import com.google.adk.events.Event;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.GetSessionConfig;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.ListEventsResponse;
import com.google.adk.sessions.ListSessionsResponse;
import com.google.adk.sessions.Session;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import lombok.extern.slf4j.Slf4j;
import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.types.utils.SerialNoGenerator;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 可持久化会话服务：内存（InMemorySessionService）为热路径，MySQL write-through。
 * 重启后 getSession 未命中时，从库中按落库顺序逐条 appendEvent 重放恢复（stateDelta 随重放自动应用）。
 * 落库失败只告警不阻断对话；恢复读取失败向上抛错（旧会话点开报错好过静默丢上下文）。
 *
 * @author caps
 */
@Slf4j
@Component
public class PersistentSessionService implements BaseSessionService {

    private final InMemorySessionService delegate = new InMemorySessionService();

    private final ISessionStorePort store;

    /** 恢复互斥锁：避免并发请求同时重放同一会话（锁对象常驻，会话量级下可忽略） */
    private final ConcurrentMap<String, Object> recoveryLocks = new ConcurrentHashMap<>();

    public PersistentSessionService(ISessionStorePort store) {
        this.store = store;
    }

    @Override
    public Single<Session> createSession(String appName, String userId, ConcurrentMap<String, Object> state, String sessionId) {
        Session session = delegate.createSession(appName, userId, state, sessionId).blockingGet();
        trySave(() -> store.saveSession(appName, userId, session.id()));
        // 默认名 "yyyy-MM-dd-0X"（当日序号）：新会话立刻可辨识，用户重命名后覆盖；
        // 命名失败不影响对话（回退首条消息作标题的旧规则）
        trySave(() -> assignDefaultTitle(appName, userId, session.id()));
        return Single.just(session);
    }

    /** 当日序号 = 该用户已用今日前缀标题的会话数 + 1；已有标题（重命名过/已命名）不覆盖 */
    private void assignDefaultTitle(String appName, String userId, String sessionId) {
        List<ChatSessionRecordVO> sessions = store.loadSessions(userId);
        boolean alreadyTitled = sessions.stream()
                .anyMatch(record -> sessionId.equals(record.getSessionId())
                        && record.getTitle() != null && !record.getTitle().isBlank());
        if (alreadyTitled) {
            return;
        }
        String title = SessionTitlePolicy.defaultTitle(LocalDate.now(),
                sessions.stream().map(ChatSessionRecordVO::getTitle).toList());
        store.renameSession(appName, userId, sessionId, title);
    }

    @Override
    public Maybe<Session> getSession(String appName, String userId, String sessionId, Optional<GetSessionConfig> config) {
        return Maybe.defer(() -> {
            if (lookup(appName, userId, sessionId) != null) {
                return delegate.getSession(appName, userId, sessionId, config);
            }
            Object lock = recoveryLocks.computeIfAbsent(appName + '|' + userId + '|' + sessionId, k -> new Object());
            Maybe<Session> result;
            try {
                synchronized (lock) {
                    // 拿到锁后二次检查，可能已被并发请求恢复
                    if (lookup(appName, userId, sessionId) != null) {
                        result = delegate.getSession(appName, userId, sessionId, config);
                    } else {
                        List<Event> events = readStoredEvents(appName, userId, sessionId);
                        if (events.isEmpty()) {
                            result = Maybe.empty();
                        } else {
                            Session replayed = delegate.createSession(appName, userId, null, sessionId).blockingGet();
                            for (Event event : events) {
                                delegate.appendEvent(replayed, event).blockingGet();
                            }
                            log.info("会话已从库恢复 app={} user={} session={} events={}", appName, userId, sessionId, events.size());
                            result = delegate.getSession(appName, userId, sessionId, config);
                        }
                    }
                }
            } finally {
                // 恢复完成即移除锁对象：不存在的会话反复 get 不再累积（防慢性泄漏）
                recoveryLocks.remove(appName + '|' + userId + '|' + sessionId);
            }
            return result;
        });
    }

    @Override
    public Single<ListSessionsResponse> listSessions(String appName, String userId) {
        return delegate.listSessions(appName, userId);
    }

    @Override
    public Completable deleteSession(String appName, String userId, String sessionId) {
        Completable result = delegate.deleteSession(appName, userId, sessionId);
        trySave(() -> store.deleteSession(appName, userId, sessionId));
        return result;
    }

    @Override
    public Single<ListEventsResponse> listEvents(String appName, String userId, String sessionId) {
        return delegate.listEvents(appName, userId, sessionId);
    }

    @Override
    public Single<Event> appendEvent(Session session, Event event) {
        Single<Event> result = delegate.appendEvent(session, event);
        // partial 是流式中间帧：不进会话（委托已跳过）也不落库
        if (!event.partial().orElse(false)) {
            trySave(() -> {
                String eventId = event.id() != null ? event.id() : SerialNoGenerator.generate("E");
                String author = event.author() != null ? event.author() : "";
                store.saveEvent(session.appName(), session.userId(), session.id(),
                        eventId, author, EventJsonCodec.write(event));
            });
        }
        return result;
    }

    private Session lookup(String appName, String userId, String sessionId) {
        return delegate.getSession(appName, userId, sessionId, Optional.empty()).blockingGet();
    }

    /** 库中事件按落库顺序反序列化；读失败显式抛出（由调用方决定报错） */
    private List<Event> readStoredEvents(String appName, String userId, String sessionId) {
        return store.loadEvents(appName, userId, sessionId).stream()
                .map(EventJsonCodec::parse)
                .toList();
    }

    /** 持久化失败不阻断对话 */
    private void trySave(SaveAction action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("会话持久化失败（对话不受影响）: {}", e.getMessage());
        }
    }

    /** 允许抛受检异常（writeValueAsString）的保存动作 */
    @FunctionalInterface
    private interface SaveAction {
        void run() throws Exception;
    }
}
