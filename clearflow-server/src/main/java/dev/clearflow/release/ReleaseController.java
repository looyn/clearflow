package dev.clearflow.release;

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
@RequestMapping("/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /** 发版计划列表（服务端筛选 + 分页；行内不含发版说明正文） */
    @GetMapping
    @RequireProject(module = "发版计划", level = "查看")
    public PageResult<Release.View> list(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String owner,
                                         @RequestParam(required = false) String q) {
        return releaseService.page(page, pageSize, status, owner, q);
    }

    /** 发版计划详情 */
    @GetMapping("/{id}")
    @RequireProject(module = "发版计划", level = "查看")
    public Release.View detail(@PathVariable String id) {
        return releaseService.view(id);
    }

    /** 已关联的发布内容（需求 / 缺陷标题与处理人，按 id 批量取） */
    @GetMapping("/{id}/linked")
    @RequireProject(module = "发版计划", level = "查看")
    public Map<String, Object> linked(@PathVariable String id) {
        return releaseService.linked(id);
    }

    /** 关联需求候选（服务端搜索 + 分页，linked 标记已关联项） */
    @GetMapping("/{id}/req-options")
    @RequireProject(module = "发版计划", level = "查看")
    public PageResult<Map<String, Object>> reqOptions(@PathVariable String id,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return releaseService.reqOptions(id, q, page, pageSize);
    }

    /** 关联缺陷候选（服务端搜索 + 分页） */
    @GetMapping("/{id}/defect-options")
    @RequireProject(module = "发版计划", level = "查看")
    public PageResult<Map<String, Object>> defectOptions(@PathVariable String id,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int pageSize) {
        return releaseService.defectOptions(id, q, page, pageSize);
    }

    /** 状态统计条 */
    @GetMapping("/stats")
    @RequireProject(module = "发版计划", level = "查看")
    public Map<String, Object> stats() {
        return releaseService.stats();
    }

    /** 发版通知邮件内容（只生成不发送） */
    @GetMapping("/{id}/mail")
    @RequireProject(module = "发版计划", level = "查看")
    public Map<String, Object> mail(@PathVariable String id) {
        return releaseService.mail(id);
    }

    /** 新建发版计划 */
    @PostMapping
    @RequireProject(module = "发版计划", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return releaseService.create(body);
    }

    /** 编辑发版计划（含状态流转与关联调整） */
    @PutMapping("/{id}")
    @RequireProject(module = "发版计划", level = "编辑")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return releaseService.update(id, patch);
    }

    /** 删除发版计划 */
    @DeleteMapping("/{id}")
    @RequireProject(module = "发版计划", level = "管理")
    public Map<String, Object> remove(@PathVariable String id) {
        return releaseService.remove(id);
    }
}
