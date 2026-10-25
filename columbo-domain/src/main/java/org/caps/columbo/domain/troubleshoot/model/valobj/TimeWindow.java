package org.caps.columbo.domain.troubleshoot.model.valobj;

import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Domain Primitive：相对时间窗（模型不感知服务器时钟，"最近1小时"由服务端换算）。
 * 构造即校验（数字+分钟/小时/天单位，中英文均可），非法直接抛出——
 * 消灭散在工具方法里的正则与 switch。
 */
public final class TimeWindow {

    /** 数字 + 单位：30m/1h/12h/2d/30min/1hour/2 hours/30分钟/2小时/1天 */
    private static final Pattern PATTERN =
            Pattern.compile("(\\d+)\\s*(minutes?|mins?|m|hours?|h|days?|d|分钟|小时|天)");

    private final Duration duration;

    private TimeWindow(Duration duration) {
        this.duration = duration;
    }

    public static TimeWindow parse(String text) {
        if (text == null || text.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "timeWindow 不能为空，支持 数字+单位，如 30m(30分钟)/1h(1小时)/12h/1d(1天)");
        }
        Matcher matcher = PATTERN.matcher(text.toLowerCase().trim());
        if (!matcher.matches()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "timeWindow 格式错误: " + text + "，支持 数字+单位，如 30m(30分钟)/1h(1小时)/12h/1d(1天)");
        }
        long amount = Long.parseLong(matcher.group(1));
        Duration duration = switch (matcher.group(2).charAt(0)) {
            case 'm', '分' -> Duration.ofMinutes(amount);
            case 'h', '小' -> Duration.ofHours(amount);
            case 'd', '天' -> Duration.ofDays(amount);
            default -> throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "timeWindow 单位不支持: " + text);
        };
        return new TimeWindow(duration);
    }

    public Duration duration() {
        return duration;
    }

    /** 以 end 为右端点换算窗口起点（相对表述一律以当前时刻为右端） */
    public LogTime beginBefore(LocalDateTime end) {
        return LogTime.of(end.minus(duration));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TimeWindow && ((TimeWindow) o).duration.equals(duration);
    }

    @Override
    public int hashCode() {
        return duration.hashCode();
    }

    @Override
    public String toString() {
        return duration.toString();
    }
}
