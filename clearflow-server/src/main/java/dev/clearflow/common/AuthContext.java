package dev.clearflow.common;

import dev.clearflow.security.MemberPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 获取当前登录人（由 JwtAuthFilter 写入 SecurityContext）
 */
public final class AuthContext {

    private AuthContext() {
    }

    public static MemberPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof MemberPrincipal principal)) {
            throw BizException.unauthorized("未登录或登录已过期");
        }
        return principal;
    }
}
