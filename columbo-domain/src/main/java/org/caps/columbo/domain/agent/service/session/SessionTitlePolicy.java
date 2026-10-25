package org.caps.columbo.domain.agent.service.session;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionRecordVO;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 领域策略：会话标题——
 * 默认名：创建时按 用户当日序号 生成 "yyyy-MM-dd-0X"（用户无需手动重命名即可区分）；
 * 自定义标题（重命名）永远优先；解析失败回退空串。
 * 原内联在 ChatService，规则收拢到领域层后应用服务只做编排。
 */
@Slf4j
public final class SessionTitlePolicy {

    private static final DateTimeFormatter DATE = LocalDateTimeUtils.DATE_FORMATTER;

    private SessionTitlePolicy() {
    }

    /** 合成展示标题：自定义（含默认名，创建时已落库）> 首条用户消息 > 空串 */
    public static String displayTitle(ChatSessionRecordVO record) {
        if (record.getTitle() != null && !record.getTitle().isBlank()) {
            return record.getTitle();
        }
        if (record.getFirstUserEventJson() == null) {
            return "";
        }
        try {
            return EventJsonCodec.parse(record.getFirstUserEventJson()).stringifyContent();
        } catch (Exception e) {
            log.warn("会话标题解析失败 session={}: {}", record.getSessionId(), e.getMessage());
            return "";
        }
    }

    /**
     * 生成默认标题 "yyyy-MM-dd-0X"：序号=该用户当日已用同前缀标题的会话数+1（两位补零，超 99 顺延三位）。
     * 例：当日第 1/2 个新会话 → 2026-09-22-01 / 2026-09-22-02。
     */
    public static String defaultTitle(LocalDate date, List<String> existingTitles) {
        String today = date.format(DATE);
        long used = existingTitles == null ? 0
                : existingTitles.stream().filter(t -> t != null && t.startsWith(today + "-")).count();
        return String.format("%s-%02d", today, used + 1);
    }
}
