package dev.clearflow.perm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.RequireAdmin;
import dev.clearflow.common.RequireOwner;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberBrief;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.member.MemberService;
import dev.clearflow.project.Project;
import dev.clearflow.project.ProjectMapper;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限接口：授权按项目隔离——同一成员在不同项目可以有不同模块等级。
 * 系统级常量仅系统管理员可读；项目矩阵由系统管理员或该项目负责人读写。
 */
@RestController
@RequestMapping("/perm")
public class PermController {

    private final PermService permService;
    private final MemberService memberService;
    private final MemberMapper memberMapper;
    private final ActivityLog activityLog;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectContext projectContext;

    public PermController(PermService permService, MemberService memberService, MemberMapper memberMapper,
                          ActivityLog activityLog, ProjectMapper projectMapper,
                          ProjectMemberMapper projectMemberMapper, ProjectContext projectContext) {
        this.permService = permService;
        this.memberService = memberService;
        this.memberMapper = memberMapper;
        this.activityLog = activityLog;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectContext = projectContext;
    }

    /** 系统级权限常量（模块 / 等级 / 角色 + 角色默认矩阵），成员管理页的角色下拉用 */
    @GetMapping
    @RequireAdmin
    public Map<String, Object> constants() {
        return permService.constants();
    }

    /** 当前登录人的权限画像：菜单过滤与按钮显隐的数据源（等级按「当前项目」计算） */
    @GetMapping("/me")
    public Map<String, Object> me() {
        MemberPrincipal me = AuthContext.require();
        Long projectId = projectContext.currentProjectIdOrNull();
        List<Long> owned = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                        .eq(Project::getOwner, me.id()))
                .stream().map(Project::getId).toList();
        boolean owner = projectId != null && owned.contains(projectId);
        Map<String, String> levels = new LinkedHashMap<>();
        for (String module : PermConstants.MODULES) {
            levels.put(module, permService.effectiveLevel(me, projectId, module, owner));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("perm", me.perm());
        result.put("isAdmin", me.admin());
        result.put("projectId", projectId);
        result.put("levels", levels);
        result.put("ownedProjectIds", owned);
        return result;
    }

    /** 某项目的权限矩阵（系统管理员或该项目负责人）：含项目成员与各自已设置过的等级 */
    @GetMapping("/matrix")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "projectId")
    public Map<String, Object> matrix(@RequestParam Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在");
        }
        List<Long> memberIds = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId))
                .stream().map(ProjectMember::getMemberId).toList();
        Map<String, Object> result = permService.constants();
        result.put("projectId", projectId);
        result.put("owner", project.getOwner());
        result.put("members", memberService.briefOf(memberIds));
        result.put("grants", permService.projectGrants(projectId, memberIds));
        result.put("effective", effectiveLevels(projectId, memberIds, project.getOwner()));
        return result;
    }

    /** 每个成员在本项目下的有效等级（项目授权覆盖 | 角色默认，负责人未授权时全权）——前端矩阵只展示，不重算规则 */
    private Map<Long, Map<String, String>> effectiveLevels(Long projectId, List<Long> memberIds, Long ownerId) {
        Map<Long, Map<String, String>> byMember = new LinkedHashMap<>();
        if (memberIds.isEmpty()) {
            return byMember;
        }
        for (Member m : memberMapper.selectBatchIds(memberIds)) {
            MemberPrincipal mp = new MemberPrincipal(m.getId(), m.getName(), m.getPerm(), false);
            boolean owner = m.getId().equals(ownerId);
            Map<String, String> levels = new LinkedHashMap<>();
            for (String module : PermConstants.MODULES) {
                levels.put(module, permService.effectiveLevel(mp, projectId, module, owner));
            }
            byMember.put(m.getId(), levels);
        }
        return byMember;
    }

    /** 调整成员在本项目内某模块的等级（PUT /perm/{memberId}?projectId=）：管理员或该项目负责人；负责人一行只有系统管理员能改 */
    @PutMapping("/{memberId}")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "projectId")
    public Map<String, Object> update(@PathVariable Long memberId,
                                      @RequestParam Long projectId,
                                      @RequestBody PermUpdateReq req) {
        var member = memberService.get(memberId); // 不存在则 404
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw BizException.notFound("项目不存在");
        }
        MemberPrincipal actor = AuthContext.require();
        if (memberId.equals(project.getOwner()) && !actor.admin()) {
            throw BizException.bad("项目负责人权限只能由系统管理员调整");
        }
        boolean inProject = projectMemberMapper.exists(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getMemberId, memberId));
        if (!inProject) {
            throw BizException.bad("该成员不在本项目内，请先加入项目");
        }
        permService.saveGrant(projectId, memberId, req.moduleKey(), req.level());
        activityLog.write("<b>" + actor.name() + "</b> 调整了成员 <b>" + member.getName()
                + "</b> 在「" + project.getName() + "」的「" + req.moduleKey() + "」权限为 " + req.level());
        return Map.of("ok", true);
    }
}
