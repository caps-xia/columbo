package org.caps.columbo.domain.agent.service.session;

import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.caps.columbo.domain.agent.adapter.port.ISessionSummarizePort;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionCompactionProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话压缩服务单测：开关/事件数门槛/转写拼接与截断/摘要截断护栏/失败降级。
 */
class SessionCompactionServiceTest {

    @Test
    void disabledReturnsNull() {
        SessionCompactionProperties properties = new SessionCompactionProperties();
        properties.setEnabled(false);
        SessionCompactionService service = new SessionCompactionService((sys, content) -> "摘要", properties);

        assertNull(service.summarize(events(5)));
    }

    @Test
    void tooFewEventsReturnsNull() {
        // 事件数低于门槛：直接翻篇几乎无损失，不值得花一次摘要调用
        SessionCompactionProperties properties = enabledProperties();
        properties.setMinEvents(3);
        SessionCompactionService service = new SessionCompactionService((sys, content) -> "摘要", properties);

        assertNull(service.summarize(events(2)));
    }

    @Test
    void summarizesTranscriptWithBothAuthors() {
        SessionCompactionProperties properties = enabledProperties();
        AtomicReference<String> receivedSystem = new AtomicReference<>();
        AtomicReference<String> receivedContent = new AtomicReference<>();
        SessionCompactionService service = new SessionCompactionService((sys, content) -> {
            receivedSystem.set(sys);
            receivedContent.set(content);
            return "  摘要正文：用户反馈南京下单失败，已定位金额校验。  ";
        }, properties);

        String summary = service.summarize(events(4));

        assertEquals("摘要正文：用户反馈南京下单失败，已定位金额校验。", summary, "首尾空白应剥离");
        assertTrue(receivedSystem.get().contains("摘要"), "SOP 提示词应随调用传入");
        assertTrue(receivedContent.get().contains("[user] 南京环境消费医疗，下单失败"));
        assertTrue(receivedContent.get().contains("[model] 已查到 ERROR 日志"), "user/model 事件都应进入转写");
    }

    @Test
    void portFailureReturnsNull() {
        SessionCompactionService service = new SessionCompactionService((sys, content) -> {
            throw new IllegalStateException("gateway timeout");
        }, enabledProperties());

        assertNull(service.summarize(events(4)), "摘要失败应降级（返回 null → 普通翻篇），不得抛出");
    }

    @Test
    void blankSummaryReturnsNull() {
        SessionCompactionService service = new SessionCompactionService((sys, content) -> "   ", enabledProperties());
        assertNull(service.summarize(events(4)));
    }

    @Test
    void oversizeSummaryTruncated() {
        SessionCompactionProperties properties = enabledProperties();
        properties.setMaxSummaryChars(50);
        SessionCompactionService service = new SessionCompactionService((sys, content) -> "长".repeat(500), properties);

        String summary = service.summarize(events(4));
        assertEquals(50 + "…（已截断）".length(), summary.length());
        assertTrue(summary.endsWith("…（已截断）"));
    }

    @Test
    void oversizeTranscriptCapped() {
        SessionCompactionProperties properties = enabledProperties();
        properties.setMaxTranscriptChars(200);
        AtomicReference<String> receivedContent = new AtomicReference<>();
        SessionCompactionService service = new SessionCompactionService((sys, content) -> {
            receivedContent.set(content);
            return "摘要";
        }, properties);

        List<Event> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(userEvent("事件" + i + "，" + "内容".repeat(30)));
        }
        service.summarize(events);

        assertTrue(receivedContent.get().length() <= 200 + 64, "转写应按上限截断");
        assertTrue(receivedContent.get().contains("过长"), "截断处应有说明标记");
    }

    @Test
    void partialEventsExcluded() {
        SessionCompactionProperties properties = enabledProperties();
        AtomicReference<String> receivedContent = new AtomicReference<>();
        SessionCompactionService service = new SessionCompactionService((sys, content) -> {
            receivedContent.set(content);
            return "摘要";
        }, properties);

        List<Event> events = new ArrayList<>(events(3));
        events.add(partialEvent("流式中间帧"));
        service.summarize(events);

        assertTrue(receivedContent.get().contains("[user] 南京环境消费医疗，下单失败"));
        assertFalse(receivedContent.get().contains("流式中间帧"), "partial 中间帧不应进入压缩");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static SessionCompactionProperties enabledProperties() {
        SessionCompactionProperties properties = new SessionCompactionProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://gateway.local");
        properties.setModel("glm-5.3-flash");
        return properties;
    }

    private static List<Event> events(int count) {
        List<Event> events = new ArrayList<>();
        events.add(userEvent("南京环境消费医疗，下单失败"));
        for (int i = 1; i < count; i++) {
            events.add(Event.builder()
                    .id("e" + i)
                    .author("model")
                    .content(Content.fromParts(Part.fromText("已查到 ERROR 日志，第 " + i + " 条线索")))
                    .build());
        }
        return events;
    }

    private static Event userEvent(String text) {
        return Event.builder()
                .id("u-" + text.hashCode())
                .author("user")
                .content(Content.fromParts(Part.fromText(text)))
                .build();
    }

    private static Event partialEvent(String text) {
        return Event.builder()
                .id("p-" + text.hashCode())
                .author("model")
                .content(Content.fromParts(Part.fromText(text)))
                .partial(true)
                .build();
    }
}
