package dev.clearflow.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 仅内置系统管理员账号（clearflow.admin-login，默认 admin）可执行：建项目、管理成员池、删除/归档项目、修改授权 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}
