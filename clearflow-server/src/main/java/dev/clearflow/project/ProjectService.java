package dev.clearflow.project;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.PageResult;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.UserPreference;
import dev.clearflow.infra.UserPreferenceMapper;
import dev.clearflow.infra.UserPreferenceService;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.perm.PermService;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectScopeService scope;
    private final UserPreferenceService preferenceService;
    private final UserPreferenceMapper preferenceMapper;
    private final MemberMapper memberMapper;
    private final PermService permService;
    private final ActivityLog activityLog;

    public ProjectService(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                          ProjectScopeService scope, UserPreferenceService preferenceService,
                          UserPreferenceMapper preferenceMapper, MemberMapper memberMapper,
                          PermService permService, ActivityLog activityLog) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.scope = scope;
        this.preferenceService = preferenceService;
        this.preferenceMapper = preferenceMapper;
        this.memberMapper = memberMapper;
        this.permService = permService;
        this.activityLog = activityLog;
    }

    /**
     * 项目列表（服务端分页；按可见范围过滤：管理员全量，其余只看自己参与的项目）
     *
     * @param state  active/planned/archived，空或 all 表示不筛
     * @param owner  负责人 member.id，前端「只看我负责的」传本人 id
     */
    public PageResult<ProjectView> page(int page, int pageSize, String keyword, String state, Long owner) {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Project::getName, kw).or().like(Project::getCode, kw));
        }
        if (state != null && !state.isBlank() && !"all".equals(state)) {
            wrapper.eq(Project::getState, state.trim());
        }
        wrapper.eq(owner != null, Project::getOwner, owner);
        scope.applyScope(wrapper, AuthContext.require());
        wrapper.orderByAsc(Project::getId);
        Page<Project> result = projectMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 200)), wrapper);
        return PageResult.of(views(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 可见项目全量（顶部切换器 / 权限矩阵等下拉用，含成员 id 列表） */
    public List<ProjectView> options() {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<>();
        scope.applyScope(wrapper, AuthContext.require());
        wrapper.orderByAsc(Project::getId);
        return views(projectMapper.selectList(wrapper));
    }

    /** 项目概览：总数 + 各状态计数 + 出现过的项目负责人（都在可见范围内） */
    public Map<String, Object> stats() {
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .select(Project::getId, Project::getState, Project::getOwner);
        scope.applyScope(wrapper, AuthContext.require());
        List<Project> rows = projectMapper.selectList(wrapper);
        Map<String, Long> byState = new java.util.LinkedHashMap<>();
        List<Long> owners = new ArrayList<>();
        for (Project p : rows) {
            byState.merge(p.getState() == null ? "" : p.getState(), 1L, Long::sum);
            if (p.getOwner() != null && !owners.contains(p.getOwner())) {
                owners.add(p.getOwner());
            }
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("total", rows.size());
        out.put("byState", byState);
        out.put("owners", owners);
        return out;
    }

    /** 项目成员明细（负责人可读邮箱，其余只给精简字段） */
    public List<Map<String, Object>> membersOf(Long projectId) {
        MemberPrincipal me = AuthContext.require();
        List<Long> ids = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId))
                .stream().map(ProjectMember::getMemberId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        boolean admin = me.admin();
        return memberMapper.selectBatchIds(ids).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(m -> memberRow(m, admin)).toList();
    }

    /** 可添加进该项目的成员候选（服务端搜索 + 分页，排除已在项目内的成员） */
    public PageResult<Map<String, Object>> memberCandidates(Long projectId, String q, int page, int pageSize) {
        MemberPrincipal me = AuthContext.require();
        List<Long> inProject = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId))
                .stream().map(ProjectMember::getMemberId).toList();
        LambdaQueryWrapper<Member> w = new LambdaQueryWrapper<>();
        if (!inProject.isEmpty()) {
            w.notIn(Member::getId, inProject);
        }
        if (q != null && !q.isBlank()) {
            String kw = q.trim();
            w.and(x -> x.like(Member::getName, kw).or().like(Member::getEmail, kw)
                    .or().like(Member::getRole, kw));
        }
        w.orderByAsc(Member::getId);
        Page<Member> result = memberMapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 100)), w);
        boolean admin = me.admin();
        return PageResult.of(result.getRecords().stream().map(m -> memberRow(m, admin)).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /** 成员行：邮箱仅系统管理员可见（与 GET /members 同口径） */
    private static Map<String, Object> memberRow(Member m, boolean admin) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("name", m.getName());
        row.put("short", m.getName() == null || m.getName().isEmpty() ? "?" : m.getName().substring(0, 1));
        row.put("color", m.getColor());
        row.put("role", m.getRole());
        row.put("email", admin ? m.getEmail() : null);
        return row;
    }

    public Project get(Long id) {
        Project project = projectMapper.selectById(id);
        if (project == null) {
            throw BizException.notFound("项目不存在");
        }
        return project;
    }

    public ProjectView view(Project project) {
        return views(List.of(project)).get(0);
    }

    /**
     * 批量组装视图（含成员 id 列表，一次 IN 查询）
     */
    public List<ProjectView> views(List<Project> projects) {
        if (projects.isEmpty()) {
            return List.of();
        }
        List<Long> ids = projects.stream().map(Project::getId).toList();
        List<ProjectMember> rows = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().in(ProjectMember::getProjectId, ids));
        Map<Long, List<Long>> membersByProject = rows.stream()
                .collect(Collectors.groupingBy(ProjectMember::getProjectId,
                        Collectors.mapping(ProjectMember::getMemberId, Collectors.toList())));
        return projects.stream()
                .map(p -> new ProjectView(p.getId(), p.getCode(), p.getName(),
                        p.getDescription(), p.getOwner(), p.getState(), p.getStartDate(), p.getEndDate(),
                        p.getColor(), membersByProject.getOrDefault(p.getId(), List.of()), p.getProgress()))
                .toList();
    }

    @Transactional
    public Long create(ProjectReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.bad("项目名称不能为空");
        }
        if (req.code() != null && !req.code().isBlank()
                && !req.code().matches("[A-Z]{2,5}")) {
            throw BizException.bad("代号须为 2~5 位大写字母");
        }
        if (req.owner() != null && memberMapper.selectById(req.owner()) == null) {
            throw BizException.bad("指定的项目负责人不存在");
        }
        Long actorId = AuthContext.require().id();
        Project project = new Project();
        project.setCode(req.code());
        project.setName(req.name().trim());
        project.setDescription(req.desc());
        project.setOwner(req.owner() != null ? req.owner() : actorId);
        project.setState(req.state() == null || req.state().isBlank() ? "planned" : req.state());
        project.setStartDate(req.start());
        project.setEndDate(req.end());
        project.setColor(req.color() == null || req.color().isBlank() ? "#3370FF" : req.color());
        project.setProgress(req.progress() != null ? req.progress() : 0);
        projectMapper.insert(project);

        ProjectMember pm = new ProjectMember();
        pm.setProjectId(project.getId());
        pm.setMemberId(project.getOwner());
        projectMemberMapper.insert(pm);

        activityLog.write("<b>" + AuthContext.require().name() + "</b> 创建项目 <span class=\"mr\">"
                + project.getCode() + "</span>「" + project.getName() + "」");
        return project.getId();
    }

    /**
     * 编辑项目（白名单字段）。成员与负责人规则：
     * ① 项目负责人由系统管理员指定（负责人只能改项目内容，不能把项目「让」给别人）；
     * ② 提交的成员必须是系统成员池里的真实成员；
     * ③ 负责人始终在项目成员内；被移出项目的人，其在本项目的模块授权一并清理。
     */
    @Transactional
    public void update(Long id, ProjectReq req) {
        Project project = get(id);
        MemberPrincipal me = AuthContext.require();
        if (req.name() != null && !req.name().isBlank()) {
            project.setName(req.name().trim());
        }
        if (req.code() != null && !req.code().isBlank()) {
            if (!req.code().matches("[A-Z]{2,5}")) {
                throw BizException.bad("代号须为 2~5 位大写字母");
            }
            project.setCode(req.code());
        }
        if (req.desc() != null) {
            project.setDescription(req.desc());
        }
        if (req.owner() != null && !req.owner().equals(project.getOwner())) {
            if (!me.admin()) {
                throw BizException.forbidden("仅系统管理员可变更项目负责人");
            }
            if (memberMapper.selectById(req.owner()) == null) {
                throw BizException.bad("指定的项目负责人不存在");
            }
            project.setOwner(req.owner());
        }
        if (req.state() != null && !req.state().isBlank()) {
            project.setState(req.state());
        }
        if (req.start() != null) {
            project.setStartDate(req.start());
        }
        if (req.end() != null) {
            project.setEndDate(req.end());
        }
        if (req.color() != null && !req.color().isBlank()) {
            project.setColor(req.color());
        }
        if (req.progress() != null) {
            project.setProgress(req.progress());
        }
        if (req.members() != null) {
            syncMembers(id, req.members());
        }
        addMemberIfAbsent(id, project.getOwner());
        projectMapper.updateById(project);
    }

    /**
     * 全量同步项目成员（「管理成员」弹窗提交全量列表：先删后插）
     */
    private void syncMembers(Long projectId, List<Long> members) {
        List<Long> wanted = new ArrayList<>(members.stream().filter(Objects::nonNull).distinct().toList());
        if (!wanted.isEmpty()) {
            Long found = memberMapper.selectCount(new LambdaQueryWrapper<Member>()
                    .in(Member::getId, wanted));
            if (found == null || found.intValue() != wanted.size()) {
                throw BizException.bad("成员列表包含不存在的账号，请刷新后重试");
            }
        }
        List<Long> before = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId))
                .stream().map(ProjectMember::getMemberId).toList();
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId));
        for (Long memberId : wanted) {
            ProjectMember pm = new ProjectMember();
            pm.setProjectId(projectId);
            pm.setMemberId(memberId);
            projectMemberMapper.insert(pm);
        }
        // 被移出本项目的人：连同他在本项目设置的模块等级一起清掉
        before.stream().filter(memberId -> !wanted.contains(memberId))
                .forEach(memberId -> permService.removeByProjectMember(projectId, memberId));
    }

    /** 负责人必须是本项目成员：缺则补上 */
    private void addMemberIfAbsent(Long projectId, Long memberId) {
        if (memberId == null) {
            return;
        }
        boolean exists = projectMemberMapper.exists(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getMemberId, memberId));
        if (!exists) {
            ProjectMember pm = new ProjectMember();
            pm.setProjectId(projectId);
            pm.setMemberId(memberId);
            projectMemberMapper.insert(pm);
        }
    }

    @Transactional
    public void archive(Long id) {
        Project project = get(id);
        project.setState("archived");
        projectMapper.updateById(project);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 归档项目 <span class=\"mr\">"
                + project.getCode() + "</span>「" + project.getName() + "」");
    }

    /**
     * 删除项目：清理项目成员引用；若有成员的当前项目被删，切到第一个剩余项目
     */
    @Transactional
    public void remove(Long id) {
        Project project = get(id);
        projectMapper.deleteById(id);
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, id));
        permService.removeByProject(id);

        Project firstRemaining = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .orderByAsc(Project::getId).last("LIMIT 1"));
        Long fallbackId = firstRemaining == null ? null : firstRemaining.getId();
        preferenceMapper.update(null, new UpdateWrapper<UserPreference>()
                .eq("current_project_id", id)
                .set("current_project_id", fallbackId));

        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除项目「" + project.getName() + "」");
    }

    /**
     * 当前项目：偏好值有效且在可见范围内则用之，否则回落到「自己参与的第一个项目」；都没有则 null
     */
    public ProjectView current(Long memberId) {
        MemberPrincipal me = AuthContext.require();
        Long prefId = preferenceService.currentProjectId(memberId);
        if (prefId != null) {
            Project project = projectMapper.selectById(prefId);
            if (project != null && scope.canAccess(me, project)) {
                return view(project);
            }
        }
        Project first = scope.firstAccessible(me);
        return first == null ? null : view(first);
    }

    public void setCurrent(Long memberId, Long projectId) {
        if (projectId != null) {
            Project p = get(projectId); // 不存在则 404
            // 切换越权修复：仅系统管理员、项目负责人或项目成员可切换到该项目
            if (!scope.canAccess(AuthContext.require(), p)) {
                throw BizException.forbidden("仅项目成员可切换到该项目");
            }
        }
        preferenceService.setCurrentProject(memberId, projectId);
    }
}
