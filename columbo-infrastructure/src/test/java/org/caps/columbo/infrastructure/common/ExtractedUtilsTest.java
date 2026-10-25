package org.caps.columbo.infrastructure.common;

import org.caps.columbo.types.utils.GzipUtils;
import org.caps.columbo.types.utils.LocalDateTimeUtils;
import org.caps.columbo.types.utils.SerialNoGenerator;
import org.caps.columbo.types.utils.sensitive.WordFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公共工具回归测试（仅覆盖在产代码使用的工具；提炼时同步重构过的点均有覆盖）：
 * 单号并发唯一、gzip 阈值与存量兼容、DFA 变形匹配、MDC 透传与同线程恢复。
 */
class ExtractedUtilsTest {

    @Test
    void serialNoFormatAndUniqueness() throws Exception {
        SerialNoGenerator.init("T");
        String no = SerialNoGenerator.generate("S", "O");
        assertTrue(no.startsWith("SOT"), no);
        assertEquals(20, no.length()); // 2 前缀 + 1 环境位 + 17 Body

        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            Thread th = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    seen.add(SerialNoGenerator.generate("E"));
                }
            });
            th.start();
            threads.add(th);
        }
        for (Thread th : threads) {
            th.join();
        }
        assertEquals(4000, seen.size(), "并发生成必须严格唯一");
    }

    @Test
    void gzipThresholdAndLegacyCompatibility() {
        String big = "会话事件内容行".repeat(6000);
        String compressed = GzipUtils.compress(big);
        assertTrue(GzipUtils.isCompressed(compressed));
        assertTrue(compressed.length() < big.length());
        assertEquals(big, GzipUtils.decompress(compressed));

        // 低于阈值原样返回；解压侧对未压缩存量数据直通
        String small = "短事件";
        assertSame(small, GzipUtils.compress(small));
        assertEquals(small, GzipUtils.decompress(small));
    }

    @Test
    void wordFilterNormalizesVariants() {
        WordFilter filter = WordFilter.of(List.of("敏感词", "sensitive"), List.of("*", " "));
        assertEquals("这是***文本", filter.doFilter("这是敏感词文本"));
        assertEquals("包含*****文本", filter.doFilter("包含敏*感*词文本"), "停顿符随命中区间一并打星");
        assertTrue(filter.isContains("包含敏 感 词 文本"), "空格停顿词绕过也命中");
        assertTrue(filter.isContains("包含ＳＥＮＳＩＴＩＶＥ字样"), "全角绕过");
        assertTrue(filter.isContains("Say SENSITIVE please"), "大小写绕过");
        assertFalse(filter.isContains("正常文本"));
        assertEquals("正常文本", filter.doFilter("正常文本"));
        assertTrue(new WordFilter().isEmpty(), "空词库直通");
        assertFalse(new WordFilter().isContains("任意内容"));
    }

    @Test
    void mdcDecoratorPropagatesAndRestores() throws InterruptedException {
        TaskDecorator decorator = MdcExecutors.taskDecorator();
        MDC.put("traceId", "t-123");
        try {
            Runnable decorated = decorator.decorate(() -> {
                if (!"t-123".equals(MDC.get("traceId"))) {
                    throw new IllegalStateException("任务内应可见提交线程的 MDC");
                }
                MDC.put("inner", "x");
            });
            // 同线程执行（CallerRuns 场景）：任务内可见提交线程 MDC，结束后原样恢复且不残留内部写入
            decorated.run();
            assertEquals("t-123", MDC.get("traceId"), "同线程执行后提交线程 MDC 不受影响");
            assertNull(MDC.get("inner"), "任务内部写入不残留");
        } finally {
            MDC.clear();
        }

        // 异线程执行（线程池场景）：任务内还原提交侧 MDC，执行线程结束后清理干净
        MDC.put("traceId", "t-456");
        try {
            Runnable decorated = decorator.decorate(() -> {
                if (!"t-456".equals(MDC.get("traceId"))) {
                    throw new IllegalStateException("池线程应还原提交线程的 MDC");
                }
            });
            Thread worker = new Thread(decorated);
            worker.start();
            worker.join();
            assertEquals("t-456", MDC.get("traceId"), "提交线程自身 MDC 不受影响");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void sharedFormatter() {
        assertEquals("2026-09-28 10:00:00.000",
                LocalDateTimeUtils.DATETIME_MILLI_FORMATTER.format(LocalDateTime.of(2026, 9, 28, 10, 0, 0)));
        assertEquals("2026-09-28 10:00:00",
                LocalDateTimeUtils.DATETIME_FORMATTER.format(LocalDateTime.of(2026, 9, 28, 10, 0, 0)));
    }
}
