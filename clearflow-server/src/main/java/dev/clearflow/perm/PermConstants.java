package dev.clearflow.perm;

import java.util.List;

/**
 * 权限矩阵常量（与前端 MemberView / mock permMatrix 保持一致）
 */
public final class PermConstants {

    private PermConstants() {
    }

    /** 六个业务模块（系统管理员专属能力——成员池、项目管理、工时/度量——不在此列，由账号身份决定） */
    public static final List<String> MODULES = List.of(
            "需求池", "迭代规划", "任务看板", "缺陷追踪", "发版计划", "文档管理");

    /**
     * 两个项目内角色：成员被加入项目时的「默认模块等级模板」。
     * 项目负责人不在其中——由 project.owner 承担，天然对本项目全权。
     */
    public static final List<String> ROLES = List.of("开发", "只读");

    /** 四个等级（升序；「无」= 该项目下看不到该模块，也调不动对应接口） */
    public static final List<String> LEVELS = List.of("无", "查看", "编辑", "管理");

    /** 项目负责人未被显式授权时的兜底等级（本项目全权） */
    public static final String OWNER_LEVEL = "管理";

    public static int rank(String level) {
        return switch (level) {
            case "管理" -> 3;
            case "编辑" -> 2;
            case "查看" -> 1;
            default -> 0;
        };
    }

    /** 角色默认等级：开发=业务模块编辑；只读=全查看；未设置按开发处理 */
    public static String defaultLevel(String perm, String module) {
        return ROLES.get(1).equals(perm) ? "查看" : "编辑";
    }
}
