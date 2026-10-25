package org.caps.columbo.domain.troubleshoot.model.valobj;

import org.caps.columbo.types.utils.LocalDateTimeUtils;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Domain Primitive：日志时间点。系统内所有日志时间的唯一合法形态——
 * 构造即完成解析与校验（yyyy-MM-dd HH:mm:ss[.SSS]，兼容 ISO 的 T 分隔，自动补毫秒），
 * 消灭裸 String 时间在层间传递（毫秒串/格式化串混用曾靠正则打补丁辨别）。
 * Immutable：无 setter，值语义。
 */
public final class LogTime {

    public static final DateTimeFormatter FORMATTER = LocalDateTimeUtils.DATETIME_MILLI_FORMATTER;

    private final LocalDateTime value;

    private LogTime(LocalDateTime value) {
        this.value = value;
    }

    /** 严格解析：T 分隔转空格、秒级补 .000、1-2 位毫秒右补零；格式非法抛 AppException(0002) */
    public static LogTime parse(String text) {
        if (text == null || text.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "时间不能为空");
        }
        String trimmed = text.trim().replace('T', ' ');
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) {
            trimmed = trimmed + ".000";
        } else if (trimmed.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{1,3}")) {
            trimmed = trimmed + "0".repeat(3 - (trimmed.length() - trimmed.lastIndexOf('.') - 1));
        }
        try {
            return new LogTime(LocalDateTime.parse(trimmed, FORMATTER));
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "时间格式错误，期望 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd HH:mm:ss.SSS，实际: " + text);
        }
    }

    /** 宽容解析：空值用默认时间（模型可不传时间，默认当天 00:00 / 当前时刻） */
    public static LogTime parseOrDefault(String text, LocalDateTime defaultTime) {
        if (text == null || text.isBlank()) {
            return new LogTime(defaultTime);
        }
        return parse(text);
    }

    /** 已校验的 LocalDateTime 包装（同包 DP 间换算用） */
    public static LogTime of(LocalDateTime value) {
        return new LogTime(value);
    }

    /** 毫秒时间戳构造（翻页游标/API 的 epoch 值） */
    public static LogTime ofEpochMilli(long epochMilli) {
        return new LogTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault()));
    }

    /** 解析外部系统返回的时间值：兼容毫秒串（13位）/秒串（10位）/格式化串三种形态 */
    public static LogTime parseApiValue(String text) {
        if (text == null || text.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "时间不能为空");
        }
        String trimmed = text.trim();
        if (trimmed.matches("\\d{13}")) {
            return ofEpochMilli(Long.parseLong(trimmed));
        }
        if (trimmed.matches("\\d{10}")) {
            return ofEpochMilli(Long.parseLong(trimmed) * 1000);
        }
        return parse(trimmed);
    }

    public LocalDateTime value() {
        return value;
    }

    /** 格式化串（yyyy-MM-dd HH:mm:ss.SSS），日志 API 与库内展示统一形态 */
    public String format() {
        return value.format(FORMATTER);
    }

    public long epochMilli() {
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LogTime && ((LogTime) o).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return format();
    }
}
