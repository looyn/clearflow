package dev.clearflow.infra;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 全局动态写入（格式与前端 mock logActivity 一致，供 GET /overview 汇聚）
 */
@Component
public class ActivityLog {

    private final OverviewActivityMapper mapper;

    public ActivityLog(OverviewActivityMapper mapper) {
        this.mapper = mapper;
    }

    public void write(String html) {
        OverviewActivity row = new OverviewActivity();
        row.setHtml(html);
        row.setCreatedAt(LocalDateTime.now());
        mapper.insert(row);
    }
}
