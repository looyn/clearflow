package dev.clearflow.worklog;

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

/** 工时登记与汇总：项目成员均可访问并登记本人工时；负责人/管理员看全团队，其余只看本人 */
@RestController
@RequestMapping("/worklog")
public class WorklogController {

    private final WorklogService worklogService;

    public WorklogController(WorklogService worklogService) {
        this.worklogService = worklogService;
    }

    @GetMapping
    @RequireProject(member = true)
    public Map<String, Object> list(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int pageSize) {
        return worklogService.page(page, pageSize);
    }

    @GetMapping("/export")
    @RequireProject(member = true)
    public ResponseEntity<byte[]> export() {
        WorklogService.Csv csv = worklogService.export();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv.data());
    }

    @PostMapping
    @RequireProject(member = true)
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return worklogService.create(body);
    }

    @PostMapping("/batch")
    @RequireProject(member = true)
    public Map<String, Object> batch(@RequestBody Map<String, Object> body) {
        return worklogService.batch(body);
    }

    @PutMapping("/{id}")
    @RequireProject(member = true)
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return worklogService.update(id, body);
    }

    @DeleteMapping("/{id}")
    @RequireProject(member = true)
    public Map<String, Object> remove(@PathVariable String id) {
        return worklogService.remove(id);
    }
}
