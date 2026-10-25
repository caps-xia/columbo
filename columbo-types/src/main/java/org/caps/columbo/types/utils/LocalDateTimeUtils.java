package org.caps.columbo.types.utils;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.Objects;

/**
 * java.time 日期时间工具：常用格式化器常量、Date⇄LocalDateTime 互转、区间/月初月末/工作日等。
 *
 * <p>提炼自 YL hospital-interface-web 的 LocalDateTimeUtils，去除了 commons-lang3 依赖。
 * 不要引入 joda-time 版 DateUtils——新代码统一用本类。
 */
public final class LocalDateTimeUtils {

    public static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATE_FORMATTER_2 = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    public static final DateTimeFormatter SMALL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yy-MM-dd");
    public static final DateTimeFormatter DATE_ZH_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月dd日");
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public static final DateTimeFormatter DATETIME_MILLI_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    public static final DateTimeFormatter DATETIME_FORMATTER_2 = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm:ss");
    public static final DateTimeFormatter TIME_FORMATTER_NOSECOND = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter DATE_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    public static final DateTimeFormatter DATE_ZH_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分");
    public static final DateTimeFormatter DATETIME_FORMATTER_SIMPLE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final ZoneOffset ZONE_CN = ZoneOffset.ofHours(8);

    private LocalDateTimeUtils() {
    }

    /* ======================= 当前时间 ======================= */

    public static LocalTime getLocalTime() {
        return LocalTime.now();
    }

    public static LocalDate getLocalDate() {
        return LocalDate.now();
    }

    public static LocalDateTime getLocalDateTime() {
        return LocalDateTime.now();
    }

    public static String getLocalTimeString() {
        return LocalTime.now().format(TIME_FORMATTER);
    }

    public static String getLocalDateString() {
        return LocalDate.now().format(DATE_FORMATTER);
    }

    public static String getSmallLocalDateString() {
        return LocalDate.now().format(SMALL_DATE_FORMATTER);
    }

    public static String getLocalDateTimeString() {
        return LocalDateTime.now().format(DATETIME_FORMATTER);
    }

    /** yyyyMMddHHmmss */
    public static String getLocalDateTimeSimpleString() {
        return LocalDateTime.now().format(DATETIME_FORMATTER_SIMPLE);
    }

    /** 当前时间秒数时间戳（东八区） */
    public static Long getCurrentSecond() {
        return LocalDateTime.now().toEpochSecond(ZONE_CN);
    }

    /* ======================= 字符串解析 ======================= */

    public static LocalTime string2LocalTime(String time) {
        return LocalTime.parse(time, TIME_FORMATTER);
    }

    public static LocalDate string2LocalDate(String date) {
        return LocalDate.parse(date, DATE_FORMATTER);
    }

    public static LocalDateTime string2LocalDateTime(String dateTime) {
        return LocalDateTime.parse(dateTime, DATETIME_FORMATTER);
    }

    public static LocalDateTime string2LocalDateTime(String dateTime, String format) {
        return LocalDateTime.parse(dateTime, DateTimeFormatter.ofPattern(format));
    }

    public static LocalDateTime string2LocalDateTime(String dateTime, String format, LocalDateTime defaultTime) {
        if (isBlank(dateTime)) {
            return defaultTime;
        }
        return LocalDateTime.parse(dateTime, DateTimeFormatter.ofPattern(format));
    }

    public static LocalDateTime string2LocalDateTime(String dateTime, DateTimeFormatter format, LocalDateTime defaultTime) {
        if (isBlank(dateTime)) {
            return defaultTime;
        }
        return LocalDateTime.parse(dateTime, format);
    }

    /** 格式是否可解析 */
    public static boolean isValidFormat(String value, DateTimeFormatter format) {
        try {
            LocalDateTime.parse(value, format);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ======================= Date 互转 ======================= */

    public static LocalDateTime date2LocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public static LocalDate date2LocalDate(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    public static LocalTime date2LocalTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
    }

    public static Date localDateTime2Date(LocalDateTime localDateTime) {
        ZonedDateTime zdt = localDateTime.atZone(ZoneId.systemDefault());
        return Date.from(zdt.toInstant());
    }

    /** 秒数时间戳 → LocalDateTime（东八区） */
    public static LocalDateTime second2LocalDateTime(Long second) {
        return LocalDateTime.ofEpochSecond(second, 0, ZONE_CN);
    }

    /** 时间 → 秒数时间戳（东八区；null 返回 0） */
    public static Long getDataTimeSecond(LocalDateTime dateTime) {
        if (Objects.isNull(dateTime)) {
            return 0L;
        }
        return dateTime.toEpochSecond(ZONE_CN);
    }

    /* ======================= 区间 / 边界 ======================= */

    /**
     * 两个日期时间差，field 取 ChronoUnit.YEARS / MONTHS 时按日历周期计算（与纯秒差不同）。
     */
    public static long betweenTwoTime(LocalDateTime startTime, LocalDateTime endTime, ChronoUnit field) {
        Period period = Period.between(LocalDate.from(startTime), LocalDate.from(endTime));
        if (field == ChronoUnit.YEARS) {
            return period.getYears();
        }
        if (field == ChronoUnit.MONTHS) {
            return period.getYears() * 12 + period.getMonths();
        }
        return field.between(startTime, endTime);
    }

    public static Duration getDuration(LocalDateTime time1, LocalDateTime time2) {
        return Duration.between(time1, time2);
    }

    /** 本月第一天 */
    public static LocalDate firstDayOfThisMonth() {
        return LocalDate.now().with(TemporalAdjusters.firstDayOfMonth());
    }

    /** 本月第 N 天 */
    public static LocalDate dayOfThisMonth(int n) {
        return LocalDate.now().withDayOfMonth(n);
    }

    /** 本月最后一天 */
    public static LocalDate lastDayOfThisMonth() {
        return LocalDate.now().with(TemporalAdjusters.lastDayOfMonth());
    }

    /** 本月第一天 00:00:00 */
    public static LocalDateTime startOfThisMonth() {
        return LocalDateTime.of(firstDayOfThisMonth(), LocalTime.MIN);
    }

    /** 本月最后一天 23:59:59.999... */
    public static LocalDateTime endOfThisMonth() {
        return LocalDateTime.of(lastDayOfThisMonth(), LocalTime.MAX);
    }

    /** 指定时间所在月第一天 00:00:00 */
    public static LocalDateTime getFirstDayOfMonth(LocalDateTime dateTime) {
        return LocalDateTime.of(LocalDate.from(dateTime.with(TemporalAdjusters.firstDayOfMonth())), LocalTime.MIN);
    }

    /** 指定时间所在年第一天 00:00:00 */
    public static LocalDateTime getFirstDayOfYear(LocalDateTime dateTime) {
        return LocalDateTime.of(LocalDate.from(dateTime.with(TemporalAdjusters.firstDayOfYear())), LocalTime.MIN);
    }

    /** 指定时间所在年最后一天末尾 */
    public static LocalDateTime getLastDayOfYear(LocalDateTime dateTime) {
        return LocalDateTime.of(LocalDate.from(dateTime.with(TemporalAdjusters.lastDayOfYear())), LocalTime.MAX);
    }

    /** 指定时间当天 00:00:00 */
    public static LocalDateTime getFirstTimeOfDay(LocalDateTime dateTime) {
        return LocalDateTime.of(dateTime.toLocalDate(), LocalTime.MIN);
    }

    /** 明天 */
    public static LocalDate getTomorrowDate() {
        return LocalDate.now().plusDays(1);
    }

    /** 添加 N 个工作日（跳过周六日，不含节假日） */
    public static LocalDateTime addWorkDays(LocalDateTime date, int workdays) {
        if (workdays < 1) {
            return date;
        }
        LocalDateTime result = date;
        int addedDays = 0;
        while (addedDays < workdays) {
            result = result.plusDays(1);
            if (!(result.getDayOfWeek() == DayOfWeek.SATURDAY || result.getDayOfWeek() == DayOfWeek.SUNDAY)) {
                ++addedDays;
            }
        }
        return result;
    }

    /** 哨兵值：未知时间（1900-01-01 00:00） */
    public static LocalDateTime getDefault() {
        return LocalDateTime.of(1900, 1, 1, 0, 0);
    }

    /** 哨兵值：最大时间（9999-12-31 00:00） */
    public static LocalDateTime getMax() {
        return LocalDateTime.of(9999, 12, 31, 0, 0);
    }

    /* ======================= 格式化 ======================= */

    /** HH:mm */
    public static String getLocalTimeString(LocalDateTime localDateTime) {
        return localDateTime.toLocalTime().format(TIME_FORMATTER_NOSECOND);
    }

    public static String getString(LocalDateTime localDateTime) {
        return localDateTime.format(DATETIME_FORMATTER);
    }

    public static String getString(LocalDateTime localDateTime, DateTimeFormatter formatter) {
        return localDateTime.format(formatter);
    }

    public static String getString(LocalDate localDate) {
        return localDate.format(DATE_FORMATTER);
    }

    public static String getDateString(LocalDateTime localDateTime) {
        return localDateTime.toLocalDate().format(DATE_FORMATTER);
    }

    public static String getDateZhString(LocalDateTime localDateTime) {
        return localDateTime.toLocalDate().format(DATE_ZH_FORMATTER);
    }

    public static LocalDate min(LocalDate v1, LocalDate v2) {
        return v1.isBefore(v2) ? v1 : v2;
    }

    public static LocalDate max(LocalDate v1, LocalDate v2) {
        return v1.isAfter(v2) ? v1 : v2;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
