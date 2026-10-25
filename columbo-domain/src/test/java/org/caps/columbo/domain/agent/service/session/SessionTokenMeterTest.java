package org.caps.columbo.domain.agent.service.session;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话 token 计量器单测：同步路径 bind/recordCurrent、流式路径 RunHandle 归属、
 * 空用量跳过、落库失败不抛出（只吞掉）。
 */
class SessionTokenMeterTest {

    @Test
    void recordCurrentUsesBoundScope() {
        RecordingSessionStore store = new RecordingSessionStore();
        SessionTokenMeter meter = new SessionTokenMeter(store);
        meter.bind("app", "u1", "s1");
        try {
            meter.recordCurrent("glm-5.3", TokenUsageVO.builder()
                    .inputTokens(100).outputTokens(50).cachedTokens(60).totalTokens(150).llmCalls(1).build());
        } finally {
            meter.unbind();
        }

        assertEquals(1, store.recordedUsage.size());
        assertEquals(150, store.recordedUsage.get(0).getTotalTokens());
    }

    @Test
    void recordViaRunHandleAttributesToRuntimeSession() {
        RecordingSessionStore store = new RecordingSessionStore();
        SessionTokenMeter meter = new SessionTokenMeter(store);
        StreamingMailbox mailbox = new StreamingMailbox(store);
        mailbox.beginRun("app", "u1", "runtime-s1");

        meter.record(mailbox.current(), "glm-5.3", TokenUsageVO.builder()
                .inputTokens(10).outputTokens(5).totalTokens(15).llmCalls(1).build());

        assertEquals(1, store.recordedUsage.size());
        assertEquals(15, store.recordedUsage.get(0).getTotalTokens());
    }

    @Test
    void nullScopeOrEmptyUsageSkipped() {
        RecordingSessionStore store = new RecordingSessionStore();
        SessionTokenMeter meter = new SessionTokenMeter(store);

        // 未绑定 scope：静默跳过（单测/无归属场景）
        meter.recordCurrent("glm-5.3", TokenUsageVO.builder().inputTokens(10).llmCalls(1).build());
        // 句柄为 null：跳过
        meter.record((StreamingMailbox.RunHandle) null, "glm-5.3",
                TokenUsageVO.builder().inputTokens(10).llmCalls(1).build());
        // 空/全 0 用量：跳过
        meter.bind("app", "u1", "s1");
        meter.recordCurrent("glm-5.3", null);
        meter.recordCurrent("glm-5.3", TokenUsageVO.builder().build());
        meter.unbind();

        assertEquals(0, store.recordedUsage.size());
    }

    @Test
    void storeFailureSwallowedNotThrown() {
        // 用量是旁路数据：落库失败只吞掉（真实实现里记 warn），不得影响对话
        SessionTokenMeter meter = new SessionTokenMeter(new RecordingSessionStore() {
            @Override
            public void recordTokenUsage(String appName, String userId, String sessionId, TokenUsageVO delta) {
                throw new IllegalStateException("db down");
            }
        });
        meter.bind("app", "u1", "s1");
        meter.recordCurrent("glm-5.3", TokenUsageVO.builder().inputTokens(10).llmCalls(1).build());
        meter.unbind();
    }

    @Test
    void unbindClearsThreadScope() {
        RecordingSessionStore store = new RecordingSessionStore();
        SessionTokenMeter meter = new SessionTokenMeter(store);
        meter.bind("app", "u1", "s1");
        meter.unbind();
        assertNull(meter.current());
        meter.recordCurrent("glm-5.3", TokenUsageVO.builder().inputTokens(10).llmCalls(1).build());
        assertEquals(0, store.recordedUsage.size(), "解绑后不得串号记账");
    }
}
