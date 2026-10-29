package dev.clearflow.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.common.RequireProject;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.iteration.Iteration;
import dev.clearflow.iteration.IterationMapper;
import dev.clearflow.release.Release;
import dev.clearflow.release.ReleaseMapper;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import dev.clearflow.settings.SettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全局搜索（⌘K）：任务/需求/缺陷/发版/迭代 命中 top 12，形状与前端 mock 一致
 */
@RestController
public class SearchController {

    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final DefectMapper defectMapper;
    private final ReleaseMapper releaseMapper;
    private final IterationMapper iterationMapper;
    private final ProjectContext projectContext;
    private final SettingsService settings;

    public SearchController(TaskMapper taskMapper, RequirementMapper requirementMapper,
                            DefectMapper defectMapper, ReleaseMapper releaseMapper,
                            IterationMapper iterationMapper, ProjectContext projectContext,
                            SettingsService settings) {
        this.taskMapper = taskMapper;
        this.requirementMapper = requirementMapper;
        this.defectMapper = defectMapper;
        this.releaseMapper = releaseMapper;
        this.iterationMapper = iterationMapper;
        this.projectContext = projectContext;
        this.settings = settings;
    }

    /** 全局搜索限定当前项目的实体：任何登录人都能用，但仅限自己参与的项目 */
    @GetMapping("/search")
    @RequireProject(member = true)
    public List<Map<String, Object>> search(@RequestParam(defaultValue = "") String q) {
        String kw = q.trim().toLowerCase();
        if (kw.isEmpty()) {
            return List.of();
        }
        Long projectId = projectContext.currentProjectId();
        List<Map<String, Object>> rows = new ArrayList<>();

        taskMapper.selectList(new LambdaQueryWrapper<Task>().eq(Task::getProjectId, projectId))
                .stream().filter(x -> hit(kw, x.getId(), x.getTitle()))
                .forEach(x -> rows.add(row("任务", "board", x.getId(), x.getTitle(),
                        settings.statusName(projectId, "columns", x.getStatus()))));

        requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, projectId))
                .stream().filter(x -> hit(kw, x.getId(), x.getTitle()))
                .forEach(x -> rows.add(row("需求", "requirements", x.getId(), x.getTitle(),
                        settings.statusName(projectId, "reqColumns", x.getStatus()))));

        defectMapper.selectList(new LambdaQueryWrapper<Defect>().eq(Defect::getProjectId, projectId))
                .stream().filter(x -> hit(kw, x.getId(), x.getTitle()))
                .forEach(x -> rows.add(row("缺陷", "defects", x.getId(), x.getTitle(),
                        settings.statusName(projectId, "defectColumns", x.getStatus()))));

        releaseMapper.selectList(new LambdaQueryWrapper<Release>().eq(Release::getProjectId, projectId))
                .stream().filter(x -> hit(kw, x.getId(), x.getVersion(), x.getName()))
                .forEach(x -> rows.add(row("发版计划", "releases", x.getId(),
                        x.getVersion() + " " + x.getName(),
                        settings.statusName(projectId, "relColumns", x.getStatus()))));

        iterationMapper.selectList(new LambdaQueryWrapper<Iteration>()
                        .eq(Iteration::getProjectId, projectId))
                .stream().filter(x -> hit(kw, x.getCode(), x.getName()))
                .forEach(x -> rows.add(row("迭代", "iterations", x.getCode(), x.getName(),
                        settings.statusName(projectId, "iterColumns", x.getState()))));

        return rows.size() > 12 ? rows.subList(0, 12) : rows;
    }

    private static boolean hit(String kw, String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            sb.append(f == null ? "" : f);
        }
        return sb.toString().toLowerCase().contains(kw);
    }

    private static Map<String, Object> row(String kind, String page, String id, String title, String status) {
        return Map.of("kind", kind, "page", page, "id", id, "title", title,
                "status", status == null ? "" : status);
    }
}
