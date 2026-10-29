package dev.clearflow.task;

import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireProject;
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
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 任务详情（看板卡片展开用） */
    @GetMapping("/{id}")
    @RequireProject(module = "任务看板", level = "查看")
    public Task.View detail(@PathVariable String id) {
        return taskService.view(id);
    }

    /** 任务列表（服务端筛选 + 排序 + 分页，看板视图按列窗口取数） */
    @GetMapping
    @RequireProject(module = "任务看板", level = "查看")
    public PageResult<Task.View> list(@RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int pageSize,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String prio,
                                      @RequestParam(required = false) String assignee,
                                      @RequestParam(required = false) String iter,
                                      @RequestParam(required = false) String module,
                                      @RequestParam(required = false) String sort) {
        return taskService.page(page, pageSize,
                BoardQuery.of(q, status, prio, assignee, iter, module, null, 0), sort);
    }

    /** 创建任务 */
    @PostMapping
    @RequireProject(module = "任务看板", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return taskService.create(body);
    }

    /** 更新任务（含看板拖拽改状态） */
    @PutMapping("/{id}")
    @RequireProject(module = "任务看板", level = "编辑")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return taskService.update(id, patch);
    }

    /** 删除任务 */
    @DeleteMapping("/{id}")
    @RequireProject(module = "任务看板", level = "管理")
    public Map<String, Object> remove(@PathVariable String id) {
        return taskService.remove(id);
    }

    // ===== 子任务 =====

    @PostMapping("/{id}/subtasks")
    @RequireProject(module = "任务看板", level = "编辑")
    public Map<String, Object> addSubtask(@PathVariable String id, @RequestBody Map<String, String> body) {
        return taskService.addSubtask(id, body.get("title"));
    }

    @PutMapping("/{id}/subtasks/{sid}")
    @RequireProject(module = "任务看板", level = "编辑")
    public Map<String, Object> toggleSubtask(@PathVariable String id, @PathVariable String sid) {
        return taskService.toggleSubtask(id, sid);
    }

    @DeleteMapping("/{id}/subtasks/{sid}")
    @RequireProject(module = "任务看板", level = "编辑")
    public Map<String, Object> removeSubtask(@PathVariable String id, @PathVariable String sid) {
        return taskService.removeSubtask(id, sid);
    }
}
