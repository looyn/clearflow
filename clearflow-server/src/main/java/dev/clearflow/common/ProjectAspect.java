package dev.clearflow.common;

import dev.clearflow.infra.ProjectContext;
import dev.clearflow.perm.ProjectAccessGuard;
import dev.clearflow.security.MemberPrincipal;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * 权限切面：
 * 1) @RequireProject：项目级三层判定（管理员 → 负责人 → 模块授权），
 *    CURRENT 模式取当前偏好项目，PARAM 模式按方法参数名取 projectId；
 * 2) @RequireOwner：仅系统管理员或项目负责人；
 * 3) @RequireAdmin：仅内置系统管理员账号。
 */
@Aspect
@Component
public class ProjectAspect {

    private final ProjectAccessGuard guard;
    private final ProjectContext projectContext;

    public ProjectAspect(ProjectAccessGuard guard, ProjectContext projectContext) {
        this.guard = guard;
        this.projectContext = projectContext;
    }

    @Around("@annotation(requireProject)")
    public Object checkProject(ProceedingJoinPoint pjp, RequireProject requireProject) throws Throwable {
        Long projectId = switch (requireProject.mode()) {
            case CURRENT -> projectContext.currentProjectId();
            case PARAM -> paramByName(pjp, requireProject.key());
        };
        if (requireProject.member()) {
            guard.checkMember(projectId);
        } else {
            guard.check(projectId, requireProject.module(), requireProject.level());
        }
        return pjp.proceed();
    }

    @Around("@annotation(requireOwner)")
    public Object checkOwner(ProceedingJoinPoint pjp, RequireOwner requireOwner) throws Throwable {
        Long projectId = switch (requireOwner.mode()) {
            case CURRENT -> projectContext.currentProjectId();
            case PARAM -> paramByName(pjp, requireOwner.key());
        };
        guard.checkOwner(projectId);
        return pjp.proceed();
    }

    @Around("@annotation(requireAdmin)")
    public Object checkAdmin(ProceedingJoinPoint pjp, RequireAdmin requireAdmin) throws Throwable {
        MemberPrincipal me = AuthContext.require();
        if (!me.admin()) {
            throw BizException.forbidden("仅系统管理员可执行该操作");
        }
        return pjp.proceed();
    }

    private Long paramByName(ProceedingJoinPoint pjp, String name) {
        MethodSignature sig = (MethodSignature) pjp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (name.equals(names[i]) && args[i] instanceof Long l) {
                return l;
            }
        }
        throw BizException.bad("无法解析项目标识：" + name);
    }
}
