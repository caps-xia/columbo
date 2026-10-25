package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 领域策略单测：标题合成优先级 + 默认名序号 + 翻篇阈值
 */
class SessionPoliciesTest {

    @Test
    void defaultTitleSequencesPerDay() {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 9, 22);
        // 当日无会话 → -01
        assertEquals("2026-09-22-01", SessionTitlePolicy.defaultTitle(today, java.util.List.of()));
        // 已有当日两个默认名 → -03
        assertEquals("2026-09-22-03", SessionTitlePolicy.defaultTitle(today,
                java.util.List.of("2026-09-22-01", "2026-09-22-02")));
        // 昨日的前缀不占今日序号；自定义标题也不占（null 容忍）
        assertEquals("2026-09-22-01", SessionTitlePolicy.defaultTitle(today,
                java.util.Arrays.asList("2026-09-21-05", "值班台账", null)));
    }

    @Test
    void displayTitlePrefersCustomTitle() {
        ChatSessionRecordVO record = ChatSessionRecordVO.builder()
                .title(" 值班台账 ")
                .firstUserEventJson("{\"author\":\"user\"}")
                .build();
        assertEquals(" 值班台账 ", SessionTitlePolicy.displayTitle(record));
    }

    @Test
    void displayTitleFallsBackToFirstUserEvent() {
        ChatSessionRecordVO record = ChatSessionRecordVO.builder()
                .title("")
                .firstUserEventJson("{\"author\":\"user\"}")
                .build();
        // EventJsonCodec 解析出内容即用；解析失败回退空串不抛错
        String title = SessionTitlePolicy.displayTitle(record);
        assertTrue(title != null);
    }

    @Test
    void displayTitleFallsBackToEmptyWhenNoSource() {
        ChatSessionRecordVO record = ChatSessionRecordVO.builder().build();
        assertEquals("", SessionTitlePolicy.displayTitle(record));
    }

    @Test
    void displayTitleToleratesBrokenJson() {
        ChatSessionRecordVO record = ChatSessionRecordVO.builder()
                .firstUserEventJson("not-a-json{{{")
                .build();
        assertEquals("", SessionTitlePolicy.displayTitle(record));
    }

    @Test
    void rolloverAtThreshold() {
        assertFalse(SessionRolloverPolicy.needRollover(79, 80));
        assertTrue(SessionRolloverPolicy.needRollover(80, 80));
        assertTrue(SessionRolloverPolicy.needRollover(81, 80));
    }
}
