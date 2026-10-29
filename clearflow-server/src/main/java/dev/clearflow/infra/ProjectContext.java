package dev.clearflow.infra;

import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.project.Project;
import dev.clearflow.project.ProjectMapper;
import dev.clearflow.project.ProjectScopeService;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Component;

/**
 * 业务表 project_id 隔离上下文：当前登录人的偏好项目；偏好失效或已越出可见范围时，
 * 回落到「自己参与的第一个项目」（管理员为全库第一个），一个都没有则明确拒绝。
 * （与 GET /projects/current 的回落逻辑一致；前端契约不传 projectId）
 */
@Component
public class ProjectContext {

    private final ProjectMapper projectMapper;
    private final UserPreferenceService preferenceService;
    private final ProjectScopeService scope;

    public ProjectContext(ProjectMapper projectMapper, UserPreferenceService preferenceService,
                          ProjectScopeService scope) {
        this.projectMapper = projectMapper;
        this.preferenceService = preferenceService;
        this.scope = scope;
    }

    public Long currentProjectId() {
        Long id = currentProjectIdOrNull();
        if (id == null) {
            throw BizException.forbidden("你还没有被分配到任何项目，请联系系统管理员");
        }
        return id;
    }

    /** 与 currentProjectId 同一套回落逻辑，但没有可见项目时返回 null（权限画像等非项目请求用） */
    public Long currentProjectIdOrNull() {
        MemberPrincipal me = AuthContext.require();
        Long prefId = preferenceService.currentProjectId(me.id());
        if (prefId != null) {
            Project pref = projectMapper.selectById(prefId);
            if (pref != null && scope.canAccess(me, pref)) {
                return prefId;
            }
        }
        Project first = scope.firstAccessible(me);
        return first == null ? null : first.getId();
    }
}
