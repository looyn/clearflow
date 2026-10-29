package dev.clearflow.requirement;

import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.CsvFile;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireProject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/requirements")
public class RequirementController {

    private final RequirementService requirementService;

    public RequirementController(RequirementService requirementService) {
        this.requirementService = requirementService;
    }

    /** 需求列表（服务端筛选 + 排序 + 分页；行内不含正文） */
    @GetMapping
    @RequireProject(module = "需求池", level = "查看")
    public PageResult<Requirement.View> list(@RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int pageSize,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String prio,
                                             @RequestParam(required = false) String owner,
                                             @RequestParam(required = false) String iter,
                                             @RequestParam(required = false) String module,
                                             @RequestParam(required = false) String sort) {
        return requirementService.page(page, pageSize,
                BoardQuery.of(q, status, prio, owner, iter, module, null, 0), sort);
    }

    /** 需求统计（总数 / 各状态 / 本季度新增 / 迭代内状态分布 / 迭代候选，全部 SQL 聚合） */
    @GetMapping("/stats")
    @RequireProject(module = "需求池", level = "查看")
    public Map<String, Object> stats(@RequestParam(required = false) String iter) {
        return requirementService.stats(iter);
    }

    /** 规划弹窗候选：未排入指定迭代、未到终态的需求（服务端搜索 + 分页；读的是需求池数据，鉴权同需求池） */
    @GetMapping("/plan-pool")
    @RequireProject(module = "需求池", level = "查看")
    public PageResult<Requirement.View> planPool(@RequestParam(required = false) String iter,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return requirementService.planPool(iter, q, page, pageSize);
    }

    /** 需求全量导出（按当前筛选，真 .csv 文件，不分页） */
    @GetMapping("/export")
    @RequireProject(module = "需求池", level = "查看")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String prio,
                                         @RequestParam(required = false) String owner,
                                         @RequestParam(required = false) String iter,
                                         @RequestParam(required = false) String module,
                                         @RequestParam(required = false) String sort) {
        CsvFile csv = requirementService.exportCsv(
                BoardQuery.of(q, status, prio, owner, iter, module, null, 0), sort);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.data());
    }

    /** 需求详情 */
    @GetMapping("/{id}")
    @RequireProject(module = "需求池", level = "查看")
    public Requirement.View detail(@PathVariable String id) {
        return requirementService.view(id);
    }

    /** 追踪链：关联任务 / 关联缺陷（按 req_id 直查，只取展示字段） */
    @GetMapping("/{id}/links")
    @RequireProject(module = "需求池", level = "查看")
    public Map<String, Object> links(@PathVariable String id) {
        return requirementService.links(id);
    }

    /** 新建需求 */
    @PostMapping
    @RequireProject(module = "需求池", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return requirementService.create(body);
    }

    /** 编辑需求 */
    @PutMapping("/{id}")
    @RequireProject(module = "需求池", level = "编辑")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return requirementService.update(id, patch);
    }

    /** 状态流转 */
    @PutMapping("/{id}/status")
    @RequireProject(module = "需求池", level = "编辑")
    public Map<String, Object> setStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        return requirementService.setStatus(id, body.get("status"));
    }

    /** 删除需求 */
    @DeleteMapping("/{id}")
    @RequireProject(module = "需求池", level = "管理")
    public Map<String, Object> remove(@PathVariable String id) {
        return requirementService.remove(id);
    }

    /** 验收标准勾选切换 */
    @PutMapping("/{id}/acceptance/{acId}")
    @RequireProject(module = "需求池", level = "编辑")
    public Map<String, Object> toggleAcceptance(@PathVariable String id, @PathVariable String acId) {
        return requirementService.toggleAcceptance(id, acId);
    }
}
