package dev.clearflow.module;

import dev.clearflow.common.RequireOwner;
import dev.clearflow.common.RequireProject;
import dev.clearflow.infra.ProjectContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 业务模块字典：全项目成员可读（表单/筛选/统计），仅负责人或系统管理员可维护 */
@RestController
@RequestMapping("/modules")
public class ProjectModuleController {

    private final ProjectModuleService service;
    private final ProjectContext projectContext;

    public ProjectModuleController(ProjectModuleService service, ProjectContext projectContext) {
        this.service = service;
        this.projectContext = projectContext;
    }

    @GetMapping
    @RequireProject(member = true)
    public List<ProjectModule.View> list() {
        return service.list(projectContext.currentProjectId());
    }

    @PostMapping
    @RequireOwner
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return service.create(projectContext.currentProjectId(), body);
    }

    @PutMapping("/{id}")
    @RequireOwner
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> patch) {
        return service.update(projectContext.currentProjectId(), id, patch);
    }

    @DeleteMapping("/{id}")
    @RequireOwner
    public Map<String, Object> remove(@PathVariable Long id) {
        return service.remove(projectContext.currentProjectId(), id);
    }
}
