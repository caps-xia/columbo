package org.caps.columbo.trigger.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 正文增量去重回归：正文已实时流式（partial 增量）而 final 事件仍带全量（为落会话），
 * SSE 侧必须只补未转发尾部，否则前端会看到整段重复。
 */
class ChatEventsControllerDedupeTest {

    @Test
    void fullForwardWhenNothingStreamed() {
        // 非流式兜底路径：账本为 0，final 全量转发（旧行为）
        assertEquals("完整正文", ChatEventsController.dedupeForwardedText("完整正文", 0));
        assertEquals("完整正文", ChatEventsController.dedupeForwardedText("完整正文", -1));
    }

    @Test
    void onlyTailForwardedWhenAllStreamed() {
        // 增量已全部转发：final 只剩空（会话落库仍保留全量，前端不重复）
        assertEquals("", ChatEventsController.dedupeForwardedText("完整正文", 4));
    }

    @Test
    void abortTailForwardedWhenStreamStoppedMidway() {
        // 流中止收尾：final = 已转发部分 + 新增尾部（系统提示），只转发尾部
        String full = "前半段已转发【系统提示】中止原因";
        assertEquals("【系统提示】中止原因", ChatEventsController.dedupeForwardedText(full, "前半段已转发".length()));
    }

    @Test
    void clampsWhenFinalShorterThanLedger() {
        // 异常防御：final 比账本还短（不应发生）→ 空，不越界不倒灌
        assertEquals("", ChatEventsController.dedupeForwardedText("短", 100));
        assertEquals("", ChatEventsController.dedupeForwardedText(null, 5) == null ? "" : "非空");
    }

    @Test
    void whitespacePartialMustNotBeDropped() {
        // 2026-10-14 线上回归：逐 token 流式下纯空白增量（单独的 "\n"/" " chunk）很常见
        //（表格行间换行、数字间空格都是独立 token）。曾用 hasText 判断把这类增量整块丢弃，
        // 前端直播文本缺换行 → markdown 表格解析失效显示原始管道符，刷新读落库全量才恢复。
        int[] ledger = {5};
        assertEquals("\n", ChatEventsController.textToFeed("\n", true, ledger));
        assertEquals(" ", ChatEventsController.textToFeed(" ", true, ledger));
        assertEquals("\n\n", ChatEventsController.textToFeed("\n\n", true, ledger));
        assertEquals(5, ledger[0]); // 空白仍算正文增量：账本必须同步计数
    }

    @Test
    void whitespacePartialKeepsLedgerAlignedWithFinalText() {
        // 场景复现：partial 序列 "AAAA" + "\n" + "BBBB" 全部转发（账本=9），
        // final 全量 "AAAA\nBBBB" 剥标记后应恰好对齐 → 尾部为空，不重复不缺失
        int[] ledger = {0};
        String[] partials = {"AAAA", "\n", "BBBB"};
        StringBuilder fed = new StringBuilder();
        for (String chunk : partials) {
            String piece = ChatEventsController.textToFeed(chunk, true, ledger);
            fed.append(piece);
            ledger[0] += piece.length();
        }
        assertEquals("AAAA\nBBBB", fed.toString());
        assertEquals("", ChatEventsController.textToFeed("AAAA\nBBBB", false, ledger));
    }

    @Test
    void whitespaceOnlyFinalTailAfterDedupeIsEmpty() {
        // final 只剩空白且账本已覆盖 → 去重后为空串，不再喂解析器（不产生空白事件噪音）
        int[] ledger = {9};
        assertEquals("", ChatEventsController.textToFeed("AAAA\nBBBB", false, ledger));
    }
}
