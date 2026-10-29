package dev.clearflow.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间格式化（展示用字符串与 mock 风格一致）
 */
public final class Times {

    public static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Times() {
    }

    /** 2026-10-12 18:30:00 */
    public static String now() {
        return LocalDateTime.now().format(FULL);
    }

    /** 2026-10-12 */
    public static String today() {
        return LocalDate.now().toString();
    }

    public static String format(LocalDateTime time) {
        return time == null ? null : time.format(FULL);
    }
}
