package dev.clearflow.module;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.clearflow.common.BizException;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 业务模块字典 CRUD + 模块归属校验（模块只能挂在所属项目下，删除前必须为空） */
@Service
public class ProjectModuleService {

    private final ProjectModuleMapper mapper;
    private final RequirementMapper requirementMapper;
    private final TaskMapper taskMapper;
    private final DefectMapper defectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final MemberMapper memberMapper;

    public ProjectModuleService(ProjectModuleMapper mapper, RequirementMapper requirementMapper,
                                TaskMapper taskMapper, DefectMapper defectMapper,
                                ProjectMemberMapper projectMemberMapper, MemberMapper memberMapper) {
        this.mapper = mapper;
        this.requirementMapper = requirementMapper;
        this.taskMapper = taskMapper;
        this.defectMapper = defectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.memberMapper = memberMapper;
    }

    public List<ProjectModule.View> list(Long projectId) {
        List<ProjectModule> rows = mapper.selectList(new LambdaQueryWrapper<ProjectModule>()
                .eq(ProjectModule::getProjectId, projectId)
                .orderByAsc(ProjectModule::getSort)
                .orderByAsc(ProjectModule::getId));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = ownerNames(rows);
        Map<Long, Long> reqCounts = counts(requirementMapper.selectMaps(new QueryWrapper<Requirement>()
                .select("module_id AS mid", "COUNT(*) AS c")
                .eq("project_id", projectId).isNotNull("module_id").groupBy("module_id")));
        Map<Long, Long> taskCounts = counts(taskMapper.selectMaps(new QueryWrapper<Task>()
                .select("module_id AS mid", "COUNT(*) AS c")
                .eq("project_id", projectId).isNotNull("module_id").groupBy("module_id")));
        Map<Long, Long> defectCounts = counts(defectMapper.selectMaps(new QueryWrapper<Defect>()
                .select("module_id AS mid", "COUNT(*) AS c")
                .eq("project_id", projectId).isNotNull("module_id").groupBy("module_id")));
        return rows.stream()
                .map(m -> new ProjectModule.View(m.getId(), m.getName(), m.getOwner(),
                        names.getOrDefault(m.getOwner(), ""), m.getDescription() == null ? "" : m.getDescription(),
                        m.getSort() == null ? 0 : m.getSort(),
                        reqCounts.getOrDefault(m.getId(), 0L),
                        taskCounts.getOrDefault(m.getId(), 0L),
                        defectCounts.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    /** 模块 id → 名称（列表/详情批量回填 moduleName，避免逐条查询） */
    public Map<Long, String> nameMap(Long projectId) {
        Map<Long, String> out = new HashMap<>();
        mapper.selectList(new LambdaQueryWrapper<ProjectModule>()
                        .eq(ProjectModule::getProjectId, projectId))
                .forEach(m -> out.put(m.getId(), m.getName()));
        return out;
    }

    /**
     * 校验模块归属：null/空 → null（表示未归类）；存在但不属于本项目 → 400。
     * 所有写入 moduleId 的入口（需求/任务/缺陷/批量更新）都必须经过这里。
     */
    public Long normalize(Long projectId, Long moduleId) {
        if (moduleId == null) {
            return null;
        }
        ProjectModule m = mapper.selectById(moduleId);
        if (m == null || !m.getProjectId().equals(projectId)) {
            throw BizException.bad("业务模块不存在或不属于当前项目");
        }
        return moduleId;
    }

    /** 关联需求所属模块（任务/缺陷未选模块时继承，保证统计口径不丢） */
    public Long moduleOfRequirement(Long projectId, String reqId) {
        if (reqId == null || reqId.isBlank()) {
            return null;
        }
        Requirement r = requirementMapper.selectById(reqId);
        return r != null && r.getProjectId().equals(projectId) ? r.getModuleId() : null;
    }

    @Transactional
    public Map<String, Object> create(Long projectId, Map<String, Object> body) {
        String name = text(body.get("name"));
        if (name == null) {
            throw BizException.bad("模块名称不能为空");
        }
        ensureUnique(projectId, name, null);
        ProjectModule m = new ProjectModule();
        m.setProjectId(projectId);
        m.setName(name);
        m.setOwner(moduleOwner(projectId, body.get("owner")));
        m.setDescription(text(body.get("description")) == null ? "" : text(body.get("description")));
        m.setSort(body.get("sort") == null ? nextSort(projectId) : intOf(body.get("sort"), 0));
        mapper.insert(m);
        return Map.of("ok", true, "id", m.getId());
    }

    @Transactional
    public Map<String, Object> update(Long projectId, Long id, Map<String, Object> patch) {
        ProjectModule m = get(projectId, id);
        if (patch.containsKey("name")) {
            String name = text(patch.get("name"));
            if (name == null) {
                throw BizException.bad("模块名称不能为空");
            }
            ensureUnique(projectId, name, id);
            m.setName(name);
        }
        if (patch.containsKey("owner")) m.setOwner(moduleOwner(projectId, patch.get("owner")));
        if (patch.containsKey("description")) m.setDescription(text(patch.get("description")) == null ? "" : text(patch.get("description")));
        if (patch.containsKey("sort")) m.setSort(intOf(patch.get("sort"), m.getSort() == null ? 0 : m.getSort()));
        mapper.updateById(m);
        return Map.of("ok", true);
    }

    @Transactional
    public Map<String, Object> remove(Long projectId, Long id) {
        get(projectId, id);
        long used = requirementMapper.selectCount(new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, projectId).eq(Requirement::getModuleId, id))
                + taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, projectId).eq(Task::getModuleId, id))
                + defectMapper.selectCount(new LambdaQueryWrapper<Defect>()
                        .eq(Defect::getProjectId, projectId).eq(Defect::getModuleId, id));
        if (used > 0) {
            throw BizException.bad("该模块下仍有 " + used + " 条需求/任务/缺陷，请先迁移后再删除");
        }
        mapper.deleteById(id);
        return Map.of("ok", true);
    }

    private ProjectModule get(Long projectId, Long id) {
        ProjectModule m = mapper.selectById(id);
        if (m == null || !m.getProjectId().equals(projectId)) {
            throw BizException.notFound("业务模块不存在或不属于当前项目");
        }
        return m;
    }

    private void ensureUnique(Long projectId, String name, Long excludeId) {
        LambdaQueryWrapper<ProjectModule> q = new LambdaQueryWrapper<ProjectModule>()
                .eq(ProjectModule::getProjectId, projectId)
                .eq(ProjectModule::getName, name);
        if (excludeId != null) {
            q.ne(ProjectModule::getId, excludeId);
        }
        if (mapper.selectCount(q) > 0) {
            throw BizException.bad("同名模块已存在：" + name);
        }
    }

    /** 模块负责人必须是本项目成员（可为空） */
    private Long moduleOwner(Long projectId, Object raw) {
        Long owner = longOf(raw);
        if (owner == null) {
            return null;
        }
        long member = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getMemberId, owner));
        if (member == 0) {
            throw BizException.bad("模块负责人必须是本项目成员");
        }
        return owner;
    }

    private int nextSort(Long projectId) {
        ProjectModule last = mapper.selectOne(new LambdaQueryWrapper<ProjectModule>()
                .eq(ProjectModule::getProjectId, projectId)
                .orderByDesc(ProjectModule::getSort).last("LIMIT 1"));
        return last == null || last.getSort() == null ? 1 : last.getSort() + 1;
    }

    private Map<Long, String> ownerNames(List<ProjectModule> rows) {
        List<Long> ids = rows.stream().map(ProjectModule::getOwner).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> out = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            memberMapper.selectBatchIds(ids).forEach(m -> out.put(m.getId(), m.getName()));
        }
        return out;
    }

    private static Map<Long, Long> counts(List<Map<String, Object>> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object mid = row.get("mid");
            Object c = row.get("c");
            if (mid instanceof Number n && c instanceof Number num) {
                out.put(n.longValue(), num.longValue());
            }
        }
        return out;
    }

    private static String text(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Long longOf(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
