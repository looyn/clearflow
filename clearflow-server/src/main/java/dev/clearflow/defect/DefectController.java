package dev.clearflow.defect;

import dev.clearflow.common.BoardQuery;
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
@RequestMapping("/defects")
public class DefectController {

    private final DefectService defectService;

    public DefectController(DefectService defectService) {
        this.defectService = defectService;
    }

    /** 缺陷列表（服务端筛选 + 排序 + 分页） */
    @GetMapping
    @RequireProject(module = "缺陷追踪", level = "查看")
    public PageResult<Defect.View> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "20") int pageSize,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String prio,
                                        @RequestParam(required = false) String owner,
                                        @RequestParam(required = false) String iter,
                                        @RequestParam(required = false) String module,
                                        @RequestParam(required = false) String sort) {
        return defectService.page(page, pageSize,
                BoardQuery.of(q, status, prio, owner, iter, module, null, 0), sort);
    }

    /** 缺陷看板列窗口：每列首页 N 张 + 真实列计数，供前端虚拟滚动按需续取 */
    @GetMapping("/board")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public Map<String, Object> boardColumns(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String prio,
                                            @RequestParam(required = false) String owner,
                                            @RequestParam(required = false) String iter,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) Integer limit) {
        return defectService.boardColumns(
                BoardQuery.of(q, status, prio, owner, iter, module, limit, BoardQuery.DEFAULT_WINDOW));
    }

    /** 缺陷看板单列续取（列内滚动到底时调用） */
    @GetMapping("/board/column")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public Map<String, Object> boardColumn(@RequestParam String status,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String prio,
                                           @RequestParam(required = false) String owner,
                                           @RequestParam(required = false) String iter,
                                           @RequestParam(required = false) String module,
                                           @RequestParam(required = false) Integer limit) {
        return defectService.boardColumnPage(status, offset,
                BoardQuery.of(q, null, prio, owner, iter, module, limit, BoardQuery.DEFAULT_WINDOW));
    }

    /** 缺陷全量导出（按当前筛选，真 .csv 文件，不分页） */
    @GetMapping("/export")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String prio,
                                         @RequestParam(required = false) String owner,
                                         @RequestParam(required = false) String iter,
                                         @RequestParam(required = false) String module,
                                         @RequestParam(required = false) String sort) {
        DefectService.Csv csv = defectService.export(
                BoardQuery.of(q, status, prio, owner, iter, module, null, 0), sort);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.data());
    }

    /** 缺陷详情 */
    @GetMapping("/{id}")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public Defect.View detail(@PathVariable String id) {
        return defectService.view(id);
    }

    /** 缺陷状态统计 */
    @GetMapping("/stats")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public Map<String, Object> stats() {
        return defectService.stats();
    }

    /** 缺陷趋势（近 14 天：新增/关闭/存量，全部真实数据） */
    @GetMapping("/trend")
    @RequireProject(module = "缺陷追踪", level = "查看")
    public Map<String, Object> trend() {
        return defectService.trend();
    }

    /** 提交缺陷 */
    @PostMapping
    @RequireProject(module = "缺陷追踪", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return defectService.create(body);
    }

    /** 编辑缺陷（含状态流转） */
    @PutMapping("/{id}")
    @RequireProject(module = "缺陷追踪", level = "编辑")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return defectService.update(id, patch);
    }

    /** 删除缺陷 */
    @DeleteMapping("/{id}")
    @RequireProject(module = "缺陷追踪", level = "管理")
    public Map<String, Object> remove(@PathVariable String id) {
        return defectService.remove(id);
    }

    /** 添加验证记录 */
    @PostMapping("/{id}/verify")
    @RequireProject(module = "缺陷追踪", level = "编辑")
    public Map<String, Object> addVerifyLog(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        return defectService.addVerifyLog(id, body.get("result"), body.get("note"));
    }

    /** 添加关联 */
    @PostMapping("/{id}/relates")
    @RequireProject(module = "缺陷追踪", level = "编辑")
    public Map<String, Object> addRelate(@PathVariable String id,
                                         @RequestBody Map<String, String> body) {
        return defectService.addRelate(id, body.get("type"), body.get("target"));
    }

    /** 移除关联 */
    @DeleteMapping("/{id}/relates/{relateId}")
    @RequireProject(module = "缺陷追踪", level = "编辑")
    public Map<String, Object> removeRelate(@PathVariable String id, @PathVariable String relateId) {
        return defectService.removeRelate(id, relateId);
    }
}
