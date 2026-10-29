package dev.clearflow.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 项目可见范围：全局唯一的判定口径。
 *
 * <p>系统管理员 = 全部项目；其余成员 = 自己负责的（project.owner）
 * 或自己在 project_member 里的项目。项目列表、当前项目回落、
 * 项目级权限校验都走这里，保证「成员只能看到自己相关的项目」。</p>
 */
@Service
public class ProjectScopeService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectScopeService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    /** 我与项目的关联：本项目负责人，或本项目成员 */
    public boolean participates(Long memberId, Project project) {
        if (project == null) {
            return false;
        }
        if (memberId.equals(project.getOwner())) {
            return true;
        }
        return projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId())
                .eq(ProjectMember::getMemberId, memberId)) > 0;
    }

    /** 我是否可见/可进入该项目（管理员全量） */
    public boolean canAccess(MemberPrincipal me, Project project) {
        return me.admin() || participates(me.id(), project);
    }

    /** 我在 project_member 表中的项目 id（不含「负责人」语义） */
    public List<Long> memberProjectIds(Long memberId) {
        return projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getMemberId, memberId))
                .stream().map(ProjectMember::getProjectId).distinct().toList();
    }

    /**
     * 可见的第一个项目：管理员 = 全库第一个；其余 = 自己参与的第一个。都没有则返回 null。
     * 用于「当前项目」在偏好失效/越权时的兜底。
     */
    public Project firstAccessible(MemberPrincipal me) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getId).last("LIMIT 1");
        if (!me.admin()) {
            List<Long> ids = memberProjectIds(me.id());
            wrapper.and(w -> {
                w.eq(Project::getOwner, me.id());
                if (!ids.isEmpty()) {
                    w.or().in(Project::getId, ids);
                }
            });
        }
        return projectMapper.selectOne(wrapper);
    }

    /** 给项目列表查询追加可见范围条件（管理员不追加，即全量） */
    public void applyScope(LambdaQueryWrapper<Project> wrapper, MemberPrincipal me) {
        if (me.admin()) {
            return;
        }
        List<Long> ids = memberProjectIds(me.id());
        wrapper.and(w -> {
            w.eq(Project::getOwner, me.id());
            if (!ids.isEmpty()) {
                w.or().in(Project::getId, ids);
            }
        });
    }
}
