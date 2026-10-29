package dev.clearflow.project;

import dev.clearflow.common.AuthContext;
import dev.clearflow.common.IdRequest;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireAdmin;
import dev.clearflow.common.RequireOwner;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** 项目列表（服务端筛选 + 分页） */
    @GetMapping
    public PageResult<ProjectView> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int pageSize,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(required = false) Long owner) {
        return projectService.page(page, pageSize, keyword, state, owner);
    }

    /** 可切换的项目下拉（精简字段，一次取全；顶部切换器用，与分页列表互不相干） */
    @GetMapping("/options")
    public java.util.List<ProjectView> options() {
        return projectService.options();
    }

    /** 项目概览（总数 / 各状态计数 / 负责人候选，均按可见范围） */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return projectService.stats();
    }

    /** 项目成员明细（管理员或项目负责人） */
    @GetMapping("/{id}/members")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "id")
    public java.util.List<Map<String, Object>> members(@PathVariable Long id) {
        return projectService.membersOf(id);
    }

    /** 可添加的成员候选（服务端搜索 + 分页，管理员或项目负责人） */
    @GetMapping("/{id}/member-candidates")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "id")
    public PageResult<Map<String, Object>> memberCandidates(@PathVariable Long id,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "20") int pageSize) {
        return projectService.memberCandidates(id, q, page, pageSize);
    }

    /** 创建项目（仅系统管理员） */
    @PostMapping
    @RequireAdmin
    public Map<String, Object> create(@RequestBody ProjectReq req) {
        Long id = projectService.create(req);
        return Map.of("ok", true, "id", id);
    }

    /** 编辑项目（管理员或项目负责人） */
    @PutMapping("/{id}")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "id")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ProjectReq req) {
        projectService.update(id, req);
        return Map.of("ok", true);
    }

    /** 归档项目（仅系统管理员） */
    @PutMapping("/{id}/archive")
    @RequireAdmin
    public Map<String, Object> archive(@PathVariable Long id) {
        projectService.archive(id);
        return Map.of("ok", true);
    }

    /** 删除项目（仅系统管理员） */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public Map<String, Object> remove(@PathVariable Long id) {
        projectService.remove(id);
        return Map.of("ok", true);
    }

    /** 当前项目 */
    @GetMapping("/current")
    public ProjectView current() {
        return projectService.current(AuthContext.require().id());
    }

    /** 切换当前项目 */
    @PutMapping("/current")
    public Map<String, Object> setCurrent(@RequestBody IdRequest req) {
        projectService.setCurrent(AuthContext.require().id(), req.id());
        return Map.of("ok", true);
    }
}
