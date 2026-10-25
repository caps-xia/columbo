package org.caps.columbo.domain.troubleshoot.model.valobj;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DP 单测：TimeWindow 解析边界 / LogEnv 渠道白名单
 */
class TimeWindowAndLogEnvTest {

    @Test
    void windowSupportsEnAndCnUnits() {
        assertEquals(Duration.ofMinutes(30), TimeWindow.parse("30m").duration());
        assertEquals(Duration.ofMinutes(45), TimeWindow.parse("45min").duration());
        assertEquals(Duration.ofHours(1), TimeWindow.parse("1h").duration());
        assertEquals(Duration.ofHours(12), TimeWindow.parse("12 hours").duration());
        assertEquals(Duration.ofDays(2), TimeWindow.parse("2d").duration());
        assertEquals(Duration.ofMinutes(30), TimeWindow.parse("30分钟").duration());
        assertEquals(Duration.ofHours(2), TimeWindow.parse("2小时").duration());
        assertEquals(Duration.ofDays(1), TimeWindow.parse("1天").duration());
    }

    @Test
    void windowRejectsBadFormat() {
        assertThrows(AppException.class, () -> TimeWindow.parse("abc"));
        assertThrows(AppException.class, () -> TimeWindow.parse("1w"));
        assertThrows(AppException.class, () -> TimeWindow.parse(""));
    }

    @Test
    void beginBeforeSubtractsDuration() {
        java.time.LocalDateTime end = java.time.LocalDateTime.of(2026, 9, 12, 18, 0);
        assertEquals("2026-09-12 17:00:00.000", TimeWindow.parse("1h").beginBefore(end).format());
    }


    @Test
    void clsEnvNormalizeKeepsValueForSiteValidation() {
        assertEquals("product", LogEnv.normalizeClsValue("PRODUCT"));
        assertEquals("qa", LogEnv.normalizeClsValue(" QA "));
        assertEquals("stage", LogEnv.normalizeClsValue("stage"));
        assertNull(LogEnv.normalizeClsValue(null));
        assertNull(LogEnv.normalizeClsValue(" "));
    }
}
