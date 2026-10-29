package dev.clearflow.perm;

import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.project.Project;
import dev.clearflow.project.ProjectMapper;
import dev.clearflow.project.ProjectScopeService;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Service;

/**
 * 项目级访问判定（与 @RequireProject 切面、CollabService 动态 kind 共用）：
 * ① 内置系统管理员全权；
 * ② 项目负责人(project.owner)默认对本项目全权，被系统管理员显式授权后按该等级；
 * ③ 其余**必须先是本项目成员**，再按功能模块授权等级（permission_grant 覆盖 | 角色默认）判断。
 */
@Service
public class ProjectAccessGuard {

    private final ProjectMapper projectMapper;
    private final PermService permService;
    private final ProjectScopeService scope;

    public ProjectAccessGuard(ProjectMapper projectMapper, PermService permService, ProjectScopeService scope) {
        this.projectMapper = projectMapper;
        this.permService = permService;
        this.scope = scope;
    }

    /** @param module 空 = 仅管理员或项目负责人（项目元数据操作，不走模块等级） */
    public void check(Long projectId, String module, String level) {
        MemberPrincipal me = AuthContext.require();
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw BizException.notFound("项目不存在");
        }
        if (me.admin()) {
            return;
        }
        boolean owner = me.id().equals(p.getOwner());
        if (owner && (module == null || module.isEmpty())) {
            return; // 项目元数据操作仍按负责人身份放行（不看模块等级）
        }
        if (!owner) {
            // 非管理员、非负责人：先要有项目成员身份，否则连看一眼都不行
            requireMember(me, p);
            if (module == null || module.isEmpty()) {
                throw BizException.forbidden("仅系统管理员或项目负责人可执行该操作");
            }
        }
        // 负责人也可被系统管理员显式降权；未授权时按本项目全权
        String effective = permService.effectiveLevel(me, projectId, module, owner);
        if (PermConstants.rank(effective) < PermConstants.rank(level)) {
            throw BizException.forbidden("无权限执行该操作：「" + module + "」需要「" + level + "」权限");
        }
    }

    /** 仅系统管理员或项目负责人（@RequireOwner）：不看模块授权，其余一律 403 */
    public void checkOwner(Long projectId) {
        MemberPrincipal me = AuthContext.require();
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw BizException.notFound("项目不存在");
        }
        if (me.admin() || me.id().equals(p.getOwner())) {
            return;
        }
        throw BizException.forbidden("仅系统管理员或项目负责人可执行该操作");
    }

    /** 只要求「本项目成员」（管理员 / 负责人 / 项目成员均可，不看模块等级） */
    public void checkMember(Long projectId) {
        MemberPrincipal me = AuthContext.require();
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw BizException.notFound("项目不存在");
        }
        if (me.admin() || me.id().equals(p.getOwner())) {
            return;
        }
        requireMember(me, p);
    }

    /** 非管理员 / 非负责人时的兜底：必须是本项目成员 */
    private void requireMember(MemberPrincipal me, Project p) {
        if (!scope.participates(me.id(), p)) {
            throw BizException.forbidden("你不是该项目成员，无权访问");
        }
    }
}
