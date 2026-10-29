package dev.clearflow.project;

import java.util.List;

/**
 * 创建 / 编辑项目请求体（与 ProjectFormModal 表单一致，表单不含成员字段；
 * members 仅由项目卡片「管理成员」弹窗提交，全量列表）
 */
public record ProjectReq(String name, String code, String desc, Long owner, String state,
                         String start, String end, String color, Integer progress,
                         List<Long> members) {
}
