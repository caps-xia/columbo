package org.caps.columbo.domain.agent.service.armory.matter.tools;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TroubleshootToolsTest {

    private final LocalDateTime base = LocalDateTime.of(2026, 8, 21, 10, 0, 0);

    @Test
    void normalizeTime_should_append_millis_when_missing() {
        assertEquals("2026-08-21 09:00:00.000", TroubleshootTools.normalizeTime("2026-08-21 09:00:00", base));
    }

    @Test
    void normalizeTime_should_keep_full_format() {
        assertEquals("2026-08-21 09:00:00.123", TroubleshootTools.normalizeTime("2026-08-21 09:00:00.123", base));
    }

    @Test
    void normalizeTime_should_pad_short_millis() {
        assertEquals("2026-08-21 09:00:00.100", TroubleshootTools.normalizeTime("2026-08-21 09:00:00.1", base));
    }

    @Test
    void normalizeTime_should_accept_iso_separator() {
        assertEquals("2026-08-21 09:00:00.000", TroubleshootTools.normalizeTime("2026-08-21T09:00:00", base));
    }

    @Test
    void normalizeTime_should_use_default_when_blank() {
        assertEquals("2026-08-21 10:00:00.000", TroubleshootTools.normalizeTime(null, base));
        assertEquals("2026-08-21 10:00:00.000", TroubleshootTools.normalizeTime("  ", base));
    }

    @Test
    void normalizeTime_should_reject_bad_format() {
        assertThrows(AppException.class, () -> TroubleshootTools.normalizeTime("08/21 09:00", base));
    }

    @Test
    void clampPageSize_should_bound_range() {
        assertEquals(20, TroubleshootTools.clampPageSize(null));
        assertEquals(1, TroubleshootTools.clampPageSize(0));
        assertEquals(1, TroubleshootTools.clampPageSize(-5));
        assertEquals(500, TroubleshootTools.clampPageSize(999));
        assertEquals(100, TroubleshootTools.clampPageSize(100));
    }

    @Test
    void blankToNull_should_trim() {
        assertNull(TroubleshootTools.blankToNull(null));
        assertNull(TroubleshootTools.blankToNull(" "));
        assertEquals("ERROR", TroubleshootTools.blankToNull(" ERROR "));
    }

    @Test
    void parseTimeWindow_should_support_en_and_cn_units() {
        assertEquals(java.time.Duration.ofMinutes(30), TroubleshootTools.parseTimeWindow("30m"));
        assertEquals(java.time.Duration.ofMinutes(45), TroubleshootTools.parseTimeWindow("45min"));
        assertEquals(java.time.Duration.ofHours(1), TroubleshootTools.parseTimeWindow("1h"));
        assertEquals(java.time.Duration.ofHours(12), TroubleshootTools.parseTimeWindow("12 hours"));
        assertEquals(java.time.Duration.ofDays(2), TroubleshootTools.parseTimeWindow("2d"));
        assertEquals(java.time.Duration.ofMinutes(30), TroubleshootTools.parseTimeWindow("30分钟"));
        assertEquals(java.time.Duration.ofHours(2), TroubleshootTools.parseTimeWindow("2小时"));
        assertEquals(java.time.Duration.ofDays(1), TroubleshootTools.parseTimeWindow("1天"));
    }

    @Test
    void parseTimeWindow_should_reject_bad_format() {
        assertThrows(AppException.class, () -> TroubleshootTools.parseTimeWindow("abc"));
        assertThrows(AppException.class, () -> TroubleshootTools.parseTimeWindow("1w"));
        assertThrows(AppException.class, () -> TroubleshootTools.parseTimeWindow(""));
    }

    @Test
    void resolveTime_should_prefer_time_window_over_explicit() {
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        LocalDateTime begin = LocalDateTime.parse(TroubleshootTools.resolveBeginTime("1h", "2020-01-01 00:00:00"), fmt);
        LocalDateTime end = LocalDateTime.parse(TroubleshootTools.resolveEndTime("1h", "2020-01-01 00:00:00"), fmt);
        long minutes = java.time.Duration.between(begin, end).toMinutes();
        assertTrue(minutes >= 59 && minutes <= 61, "1h 窗口应为1小时，实际 " + minutes);
        assertTrue(end.isAfter(LocalDateTime.now().minusMinutes(1)), "endTime 应为当前时间");
    }

    @Test
    void resolveTime_should_fall_back_to_explicit_times() {
        assertEquals("2026-08-21 09:00:00.000",
                TroubleshootTools.resolveBeginTime(null, "2026-08-21 09:00:00"));
        assertEquals("2026-08-21 09:00:00.000",
                TroubleshootTools.resolveEndTime("  ", "2026-08-21 09:00:00"));
    }

}
