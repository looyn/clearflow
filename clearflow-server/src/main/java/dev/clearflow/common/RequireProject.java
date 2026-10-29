package dev.clearflow.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 项目级权限校验（三层判定：系统管理员 → 项目负责人 → 本项目成员 × 模块授权等级）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireProject {

    /** 业务模块（PermConstants.MODULES）；空串 = 仅管理员或项目负责人（项目元数据操作） */
    String module() default "";

    /** 所需等级，默认编辑 */
    String level() default "编辑";

    /** true = 只要求「本项目成员」（管理员 / 负责人 / 项目成员均可），忽略 module / level */
    boolean member() default false;

    /** projectId 解析方式 */
    Mode mode() default Mode.CURRENT;

    /** PARAM 模式的方法参数名 */
    String key() default "";

    enum Mode {
        /** 当前登录人的偏好项目（ProjectContext.currentProjectId()） */
        CURRENT,
        /** 从方法参数中按名取（路径变量 projectId） */
        PARAM
    }
}
