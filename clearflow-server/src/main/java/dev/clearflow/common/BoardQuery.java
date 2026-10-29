package dev.clearflow.common;

/**
 * 看板/列表共用的筛选条件（从 query string 解析，全部下推 SQL）。
 * owner 对任务表示 assignee、对缺陷表示 owner。
 */
public record BoardQuery(String q, String status, String prio, Long owner, String iter, Long moduleId,
                         int limit) {

    public static final int MAX_LIMIT = 200;

    /** 看板列首屏窗口：一屏可见卡片数上下留足缓冲，滚动到底再续取 */
    public static final int DEFAULT_WINDOW = 40;

    public static BoardQuery of(String q, String status, String prio, String owner, String iter,
                                String module, Integer limit, int defaultLimit) {
        return new BoardQuery(blankToNull(q), blankToNull(status), blankToNull(prio),
                parseLong(owner), blankToNull(iter), parseLong(module),
                limit == null || limit <= 0 ? defaultLimit : Math.min(limit, MAX_LIMIT));
    }

    /** 是否带筛选：决定列头计数能否复用工作流全量统计 */
    public boolean filtered() {
        return q != null || status != null || prio != null || owner != null || iter != null
                || moduleId != null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Long parseLong(String s) {
        String v = blankToNull(s);
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            throw BizException.bad("筛选参数不合法：" + s);
        }
    }

    /** LIKE 模式：% 与 _ 转义，避免用户输入的通配符扩大匹配范围 */
    public String likePattern() {
        return q == null ? null : '%' + q.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + '%';
    }
}
