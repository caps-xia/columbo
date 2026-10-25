package org.caps.columbo.trigger.http.support;

import org.caps.columbo.trigger.http.support.ThinkMarkerStreamParser.Segment;
import org.caps.columbo.trigger.http.support.ThinkMarkerStreamParser.SegmentType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 思考标记流式解析：重点覆盖"标记被拆在多个流事件里"的场景（半截标记暂存、拼接后再切分）。
 */
class ThinkMarkerStreamParserTest {

    private List<Segment> parseAll(List<String> chunks) {
        List<Segment> out = new ArrayList<>();
        ThinkMarkerStreamParser parser = new ThinkMarkerStreamParser();
        for (String chunk : chunks) {
            parser.feed(chunk, out::add);
        }
        parser.flush(out::add);
        return out;
    }

    @Test
    void 整段文本_无标记() {
        List<Segment> segments = parseAll(List.of("你好", "，世界"));
        assertEquals(2, segments.size());
        assertEquals(SegmentType.TEXT, segments.get(0).type());
        assertEquals("你好", segments.get(0).content());
        assertEquals("，世界", segments.get(1).content());
    }

    @Test
    void 完整思考标记_单事件() {
        List<Segment> segments = parseAll(List.of("⟦think⟧分析中⟦/think⟧⟦thinktime⟧1200 毫秒⟦/thinktime⟧结论"));
        assertEquals(3, segments.size());
        assertEquals(SegmentType.THINK, segments.get(0).type());
        assertEquals("分析中", segments.get(0).content());
        assertEquals(SegmentType.THINK_TIME, segments.get(1).type());
        assertEquals(SegmentType.TEXT, segments.get(2).type());
        assertEquals("结论", segments.get(2).content());
        assertEquals(1200L, ThinkMarkerStreamParser.parseCostMs(segments.get(1).content()));
    }

    @Test
    void 标记拆在多个事件_半截暂存() {
        // "⟦think⟧" 被拆成 "⟦th" + "ink⟧"，前半截必须扣住不吐；正文增量可分多条（前端负责拼接）
        List<Segment> segments = parseAll(List.of("前文⟦th", "ink⟧推理⟦/think⟧正", "文"));
        String text = join(segments, SegmentType.TEXT);
        String think = join(segments, SegmentType.THINK);
        assertEquals("前文正文", text);
        assertEquals("推理", think);
        assertEquals(1, count(segments, SegmentType.THINK));
    }

    @Test
    void 结束标记跨事件() {
        List<Segment> segments = parseAll(List.of("⟦think⟧思", "考⟦/thin", "k⟧答案"));
        assertEquals("思考", join(segments, SegmentType.THINK));
        assertEquals("答案", join(segments, SegmentType.TEXT));
    }

    @Test
    void 未闭合标记_flush吐出() {
        List<Segment> segments = parseAll(List.of("正文⟦t"));
        assertEquals(2, segments.size());
        assertEquals("正文", segments.get(0).content());
        assertEquals("⟦t", segments.get(1).content());
    }

    @Test
    void 耗时解析容错() {
        assertEquals(888L, ThinkMarkerStreamParser.parseCostMs("888 毫秒"));
        assertNull(ThinkMarkerStreamParser.parseCostMs("无数字"));
        assertNull(ThinkMarkerStreamParser.parseCostMs(null));
    }

    private static String join(List<Segment> segments, SegmentType type) {
        StringBuilder sb = new StringBuilder();
        for (Segment segment : segments) {
            if (segment.type() == type) {
                sb.append(segment.content());
            }
        }
        return sb.toString();
    }

    private static long count(List<Segment> segments, SegmentType type) {
        return segments.stream().filter(s -> s.type() == type).count();
    }
}
