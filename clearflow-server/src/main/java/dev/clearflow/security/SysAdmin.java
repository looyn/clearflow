package dev.clearflow.security;

import dev.clearflow.member.Member;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 内置系统管理员账号判定。
 *
 * <p>系统管理员唯一，由配置 {@code clearflow.admin-login} 指定（默认 {@code admin}），
 * 与 member.perm 角色解耦——perm 只决定「项目内」各业务模块的默认等级，
 * 不再等同于系统管理员。</p>
 */
@Component
public class SysAdmin {

    private final String login;

    public SysAdmin(@Value("${clearflow.admin-login:admin}") String login) {
        this.login = login == null ? "" : login.trim().toLowerCase();
    }

    /** 该成员是否为内置系统管理员账号 */
    public boolean is(Member member) {
        return member != null && member.getLogin() != null
                && !login.isEmpty() && login.equals(member.getLogin().trim().toLowerCase());
    }
}
