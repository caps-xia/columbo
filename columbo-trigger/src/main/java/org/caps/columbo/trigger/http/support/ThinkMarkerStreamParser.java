package org.caps.columbo.trigger.http.support;

import java.util.List;

/**
 * 思考标记流式解析器：把模型流里的 ⟦think⟧…⟦/think⟧⟦thinktime⟧N 毫秒⟦/thinktime⟧ 标记
 * 实时切成 THINK / THINK_TIME / TEXT 三类片段（标记可能被拆在多个流事件里，未闭合的
 * 半个标记暂存等待后续拼接）。text 事件里既有增量正文也可能混有标记，统一在本类消化。
 */
public class ThinkMarkerStreamParser {

    private static final String THINK_OPEN = "⟦think⟧";
    private static final String THINK_CLOSE = "⟦/think⟧";
    private static final String TIME_OPEN = "⟦thinktime⟧";
    private static final String TIME_CLOSE = "⟦/thinktime⟧";
    private static final List<String> MARKERS = List.of(THINK_OPEN, THINK_CLOSE, TIME_OPEN, TIME_CLOSE);
    private static final int MAX_MARKER_LEN = MARKERS.stream().mapToInt(String::length).max().orElse(0);

    public enum SegmentType { THINK, THINK_TIME, TEXT }

    public record Segment(SegmentType type, String content) {
    }

    public interface SegmentConsumer {
        void accept(Segment segment);
    }

    private final StringBuilder buffer = new StringBuilder();
    private boolean inThink = false;
    private boolean inTime = false;

    public void feed(String chunk, SegmentConsumer consumer) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        buffer.append(chunk);
        drain(consumer, false);
    }

    /** 流结束时调用，吐出剩余内容（含未成形的半个标记） */
    public void flush(SegmentConsumer consumer) {
        drain(consumer, true);
    }

    private void drain(SegmentConsumer consumer, boolean finalFlush) {
        int pos = 0;
        while (true) {
            int bestIdx = -1;
            String bestMarker = null;
            for (String marker : MARKERS) {
                int idx = buffer.indexOf(marker, pos);
                if (idx >= 0 && (bestMarker == null || idx < bestIdx)) {
                    bestIdx = idx;
                    bestMarker = marker;
                }
            }
            if (bestMarker == null) {
                break;
            }
            emit(consumer, buffer.substring(pos, bestIdx));
            switch (bestMarker) {
                case THINK_OPEN -> inThink = true;
                case THINK_CLOSE -> inThink = false;
                case TIME_OPEN -> inTime = true;
                case TIME_CLOSE -> inTime = false;
                default -> {
                }
            }
            pos = bestIdx + bestMarker.length();
        }
        if (!finalFlush && inTime) {
            // 耗时数字可能被拆在多个流事件里（如 "12"+"34 毫秒"）：缓冲到 ⟦/thinktime⟧
            // 之后再整体吐出，保证一次 think_done 携带完整耗时
            buffer.delete(0, pos);
            return;
        }
        int cut = finalFlush ? buffer.length() : safeCut(pos);
        if (cut > pos) {
            emit(consumer, buffer.substring(pos, cut));
        }
        buffer.delete(0, cut);
    }

    /** 尾部若是某个标记的前缀（如 "⟦th"），先扣住不吐，等下一个流事件拼接 */
    private int safeCut(int from) {
        int length = buffer.length();
        int maxHold = Math.min(MAX_MARKER_LEN - 1, length - from);
        for (int hold = maxHold; hold >= 1; hold--) {
            String tail = buffer.substring(length - hold, length);
            for (String marker : MARKERS) {
                if (marker.startsWith(tail)) {
                    return length - hold;
                }
            }
        }
        return length;
    }

    private void emit(SegmentConsumer consumer, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        SegmentType type = inThink ? SegmentType.THINK : (inTime ? SegmentType.THINK_TIME : SegmentType.TEXT);
        consumer.accept(new Segment(type, content));
    }

    /** "1234 毫秒" → 1234；解析失败返回 null */
    public static Long parseCostMs(String thinkTimeContent) {
        if (thinkTimeContent == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(thinkTimeContent);
        return matcher.find() ? Long.parseLong(matcher.group()) : null;
    }
}
