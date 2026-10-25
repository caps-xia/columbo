package org.caps.columbo.domain.troubleshoot.model.valobj;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DP 单测：LogTime 解析/兼容/往返换算
 */
class LogTimeTest {

    @Test
    void parseAppendsMillisWhenMissing() {
        assertEquals("2026-08-21 09:00:00.000", LogTime.parse("2026-08-21 09:00:00").format());
    }

    @Test
    void parsePadsShortMillis() {
        assertEquals("2026-08-21 09:00:00.100", LogTime.parse("2026-08-21 09:00:00.1").format());
    }

    @Test
    void parseAcceptsIsoSeparator() {
        assertEquals("2026-08-21 09:00:00.000", LogTime.parse("2026-08-21T09:00:00").format());
    }

    @Test
    void parseOrDefaultUsesFallbackOnBlank() {
        java.time.LocalDateTime base = java.time.LocalDateTime.of(2026, 8, 21, 10, 0);
        assertEquals("2026-08-21 10:00:00.000", LogTime.parseOrDefault(null, base).format());
        assertEquals("2026-08-21 10:00:00.000", LogTime.parseOrDefault("  ", base).format());
    }

    @Test
    void parseRejectsBadFormat() {
        assertThrows(AppException.class, () -> LogTime.parse("08/21 09:00"));
    }

    @Test
    void parseApiValueSupportsMilliAndEpochSeconds() {
        assertEquals(1757682960123L, LogTime.parseApiValue("1757682960123").epochMilli());
        assertEquals(1757682960000L, LogTime.parseApiValue("1757682960").epochMilli());
        assertEquals("2026-08-21 09:00:00.116", LogTime.parseApiValue("2026-08-21 09:00:00.116").format());
    }

    @Test
    void epochMilliRoundTrip() {
        long now = System.currentTimeMillis();
        assertEquals(now, LogTime.ofEpochMilli(now).epochMilli());
    }
}
