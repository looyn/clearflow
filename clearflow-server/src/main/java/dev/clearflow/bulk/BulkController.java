package dev.clearflow.bulk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.defect.DefectService;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.release.Release;
import dev.clearflow.release.ReleaseMapper;
import dev.clearflow.release.ReleaseService;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.requirement.RequirementService;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import dev.clearflow.task.TaskService;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 批量更新：每条走领域 update（触发状态机与关键字段动态），概览记一条汇总
 */
@RestController
public class BulkController {

    private static final Map<String, String> KIND_LABEL = Map.of(
            "task", "任务", "defect", "缺陷",
            "req", "需求", "requirement", "需求", "release", "发版计划");

    private final TaskService taskService;
    private final RequirementService requirementService;
    private final DefectService defectService;
    private final ReleaseService releaseService;
    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final DefectMapper defectMapper;
    private final ReleaseMapper releaseMapper;
    private final CollabService collab;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;

    public BulkController(TaskService taskService, RequirementService requirementService,
                          DefectService defectService, ReleaseService releaseService,
                          TaskMapper taskMapper, RequirementMapper requirementMapper,
                          DefectMapper defectMapper, ReleaseMapper releaseMapper,
                          CollabService collab, ProjectContext projectContext,
                          ActivityLog activityLog) {
        this.taskService = taskService;
        this.requirementService = requirementService;
        this.defectService = defectService;
        this.releaseService = releaseService;
        this.taskMapper = taskMapper;
        this.requirementMapper = requirementMapper;
        this.defectMapper = defectMapper;
        this.releaseMapper = releaseMapper;
        this.collab = collab;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
    }

    @PutMapping("/bulk/update")
    public Map<String, Object> bulkUpdate(@RequestBody Map<String, Object> body) {
        String kind = CollabService.canon(String.valueOf(body.get("kind")));
        List<?> rawIds = body.get("ids") instanceof List<?> list ? list : List.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> patch = body.get("patch") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();

        collab.requireModule(kind, "编辑");
        Long projectId = projectContext.currentProjectId();

        // 仅更新属于当前项目的命中实体（与 mock 静默跳过不存在 id 的语义一致）
        List<String> ids = rawIds.stream().map(String::valueOf)
                .filter(id -> exists(kind, id, projectId)).toList();

        for (String id : ids) {
            switch (kind) {
                case "task" -> taskService.update(id, patch);
                case "requirement" -> requirementService.update(id, patch);
                case "defect" -> defectService.update(id, patch);
                case "release" -> releaseService.update(id, patch);
                default -> throw BizException.bad("不支持批量操作的类型：" + kind);
            }
        }

        MemberPrincipal actor = AuthContext.require();
        activityLog.write("<b>" + actor.name() + "</b> 批量更新 " + ids.size() + " 个"
                + KIND_LABEL.getOrDefault(kind, "条目"));
        return Map.of("ok", true, "count", ids.size());
    }

    /**
     * 看板拖拽单点落位：前端只给被拖卡片、锚点卡片和落点方向，列内其余行的位置由服务端按
     * sort 邻接关系中点计算，因此列只加载窗口（虚拟滚动）也能排出正确顺序。
     */
    @PutMapping("/bulk/move")
    public Map<String, Object> move(@RequestBody Map<String, Object> body) {
        String kind = CollabService.canon(String.valueOf(body.get("kind")));
        if (!"task".equals(kind) && !"defect".equals(kind)) {
            throw BizException.bad("该类型不支持拖拽排序：" + kind);
        }
        String id = blankToNull(body.get("id"));
        String status = blankToNull(body.get("status"));
        if (id == null || status == null) {
            throw BizException.bad("缺少拖拽条目或状态列");
        }
        collab.requireModule(kind, "编辑");
        Long projectId = projectContext.currentProjectId();
        if (!belongsTo(kind, id, projectId, status)) {
            return Map.of("ok", false);
        }
        String anchorId = blankToNull(body.get("anchorId"));
        boolean after = Boolean.parseBoolean(String.valueOf(body.get("after")));
        switch (kind) {
            case "task" -> taskService.moveInColumn(projectId, status, id, anchorId, after);
            default -> defectService.moveInColumn(projectId, status, id, anchorId, after);
        }
        return Map.of("ok", true);
    }

    private static String blankToNull(Object v) {
        String s = v == null ? null : String.valueOf(v);
        return s == null || s.isBlank() || "null".equals(s) ? null : s;
    }

    /** 排序条目校验：属于当前项目且状态就是目标列（状态尚未改完的条目自动跳过） */
    private boolean belongsTo(String kind, String id, Long projectId, String status) {
        return switch (kind) {
            case "task" -> taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getId, id).eq(Task::getProjectId, projectId)
                    .eq(Task::getStatus, status)) > 0;
            default -> defectMapper.selectCount(new LambdaQueryWrapper<Defect>()
                    .eq(Defect::getId, id).eq(Defect::getProjectId, projectId)
                    .eq(Defect::getStatus, status)) > 0;
        };
    }

    private boolean exists(String kind, String id, Long projectId) {
        return switch (kind) {
            case "task" -> taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getId, id).eq(Task::getProjectId, projectId)) > 0;
            case "requirement" -> requirementMapper.selectCount(new LambdaQueryWrapper<Requirement>()
                    .eq(Requirement::getId, id).eq(Requirement::getProjectId, projectId)) > 0;
            case "defect" -> defectMapper.selectCount(new LambdaQueryWrapper<Defect>()
                    .eq(Defect::getId, id).eq(Defect::getProjectId, projectId)) > 0;
            case "release" -> releaseMapper.selectCount(new LambdaQueryWrapper<Release>()
                    .eq(Release::getId, id).eq(Release::getProjectId, projectId)) > 0;
            default -> false;
        };
    }
}
