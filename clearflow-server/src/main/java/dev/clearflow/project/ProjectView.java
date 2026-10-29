package dev.clearflow.project;

import java.util.List;

/**
 * 项目对外视图（与 mock project 字段一致：desc/start/end 展示字符串）
 */
public record ProjectView(
        Long id,
        String code,
        String name,
        String desc,
        Long owner,
        String state,
        String start,
        String end,
        String color,
        List<Long> members,
        Integer progress) {
}
