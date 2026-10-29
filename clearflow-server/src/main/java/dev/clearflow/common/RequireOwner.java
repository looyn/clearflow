package dev.clearflow.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 仅系统管理员或项目负责人（语义化别名：等价于 module 为空的 @RequireProject，
 * 但不经过模块授权路径——非负责人一律 403）。
 * 用于工时/度量/项目设置/权限矩阵/项目元数据等「项目级管理面」端点。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireOwner {

    /** projectId 解析方式 */
    Mode mode() default Mode.CURRENT;

    /** PARAM 模式的方法参数名 */
    String key() default "";

    enum Mode {
        /** 当前登录人的偏好项目（ProjectContext.currentProjectId()） */
        CURRENT,
        /** 从方法参数中按名取（路径变量 projectId / id） */
        PARAM
    }
}
