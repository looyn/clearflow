package dev.clearflow.security;

/**
 * 登录人主体（存入 JWT / SecurityContext）
 *
 * @param id    member.id
 * @param name  姓名
 * @param perm  角色：开发 / 只读（只决定「项目内」各模块的默认等级）
 * @param admin 是否内置系统管理员账号（唯一，可看全部数据、操作一切）
 */
public record MemberPrincipal(Long id, String name, String perm, boolean admin) {
}
