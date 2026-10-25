package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CLS 渠道端口纯函数：logId（PkgId-PkgLogId）解析 + histogram 桶宽选择
 */
class ClsLogChannelPortHelpersTest {

    @Test
    void parseLogIdSplitsOnLastDash() {
        // PkgId 自身是带连字符的 uuid，按最后一个 - 切分
        assertArrayEquals(new String[]{"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "7"},
                ClsLogChannelPort.parseLogId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-7"));
        assertArrayEquals(new String[]{"abc", "12"}, ClsLogChannelPort.parseLogId(" abc-12 "));
    }

    @Test
    void parseLogIdRejectsBadFormat() {
        assertThrows(AppException.class, () -> ClsLogChannelPort.parseLogId("abc"));
        assertThrows(AppException.class, () -> ClsLogChannelPort.parseLogId("abc-xyz"));
        assertThrows(AppException.class, () -> ClsLogChannelPort.parseLogId(null));
        assertThrows(AppException.class, () -> ClsLogChannelPort.parseLogId("abc-"));
    }

    @Test
    void histogramSqlChoosesIntervalByRange() {
        long h = 60 * 60 * 1000L;
        assertTrue(ClsLogChannelPort.histogramSql(0, h).contains("interval 5 minute"));
        assertTrue(ClsLogChannelPort.histogramSql(0, 6 * h).contains("interval 30 minute"));
        assertTrue(ClsLogChannelPort.histogramSql(0, 24 * h).contains("interval 1 hour"));
        assertTrue(ClsLogChannelPort.histogramSql(0, 3 * 24 * h).contains("interval 6 hour"));
        assertTrue(ClsLogChannelPort.histogramSql(0, 30 * 24 * h).contains("interval 1 day"));
        // 结构：检索条件 | select histogram(...) as t, count(*) as cnt group by t order by t
        String sql = ClsLogChannelPort.histogramSql(0, h);
        assertTrue(sql.startsWith(" | select histogram(cast(__TIMESTAMP__ as timestamp), interval"));
        assertTrue(sql.contains(") as t, count(*) as cnt group by t order by t"));
    }
}
