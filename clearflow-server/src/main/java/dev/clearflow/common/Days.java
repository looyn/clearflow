package dev.clearflow.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

/** 聚合统计用日期工具：宽松解析 / 迭代窗口逐日展开（概览、度量、工时共用） */
public final class Days {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Days() {
    }

    /** '2026-10-09 00:00:00' / '2026-10-09' / '10.09' → LocalDate，无法解析返回 null */
    public static LocalDate parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        try {
            if (t.length() >= 10 && t.charAt(4) == '-') {
                return LocalDate.parse(t.substring(0, 10), DAY);
            }
            if (t.length() >= 5 && t.charAt(2) == '.') {
                int m = Integer.parseInt(t.substring(0, 2));
                int d = Integer.parseInt(t.substring(3, 5));
                return LocalDate.of(LocalDate.now().getYear(), m, d);
            }
        } catch (RuntimeException ignored) {
            // 自由文本日期（待排期 / — 等）按无法解析处理
        }
        return null;
    }

    /** 迭代 range '2026-10-08 ~ 2026-10-14' → 逐日列表；无效返回空，跨度上限 366 天 */
    public static List<LocalDate> range(String range) {
        List<LocalDate> out = new ArrayList<>();
        if (range == null || range.isBlank()) {
            return out;
        }
        String[] parts = range.split("~");
        LocalDate start = parse(parts[0]);
        LocalDate end = parse(parts[parts.length - 1]);
        if (start == null || end == null || end.isBefore(start)) {
            return out;
        }
        for (LocalDate d = start; !d.isAfter(end) && out.size() < 366; d = d.plusDays(1)) {
            out.add(d);
        }
        return out;
    }

    /** 有效迭代窗口，否则近 7 天（含今天） */
    public static List<LocalDate> window(String range) {
        List<LocalDate> days = range(range);
        if (!days.isEmpty()) {
            return days;
        }
        LocalDate today = LocalDate.now();
        List<LocalDate> out = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            out.add(today.minusDays(i));
        }
        return out;
    }

    /** 本周一（ISO 周） */
    public static LocalDate monday() {
        return LocalDate.now().with(WeekFields.ISO.dayOfWeek(), 1);
    }
}
