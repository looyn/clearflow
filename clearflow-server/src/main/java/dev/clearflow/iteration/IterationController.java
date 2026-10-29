package dev.clearflow.iteration;

import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireProject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/iterations")
public class IterationController {

    private final IterationService iterationService;

    public IterationController(IterationService iterationService) {
        this.iterationService = iterationService;
    }

    /** 迭代容量 / 统计 / 燃尽卡片（服务端聚合，列表数据另走分页接口） */
    @GetMapping
    @RequireProject(module = "迭代规划", level = "查看")
    public Map<String, Object> list() {
        return iterationService.list();
    }

    /** 迭代表分页（scope = current 未完成 / released 已发布 / all；行内不含复盘正文） */
    @GetMapping("/page")
    @RequireProject(module = "迭代规划", level = "查看")
    public PageResult<Iteration.View> page(@RequestParam(required = false) String scope,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int pageSize) {
        return iterationService.page(scope, page, pageSize);
    }

    /** 甘特时间轴：全部迭代一次取全（轻量字段，不含复盘正文） */
    @GetMapping("/timeline")
    @RequireProject(module = "迭代规划", level = "查看")
    public List<Iteration.View> timeline() {
        return iterationService.timeline();
    }

    /** 迭代详情（含复盘，结构化复盘返回对象、历史纯文本返回字符串） */
    @GetMapping("/{code}")
    @RequireProject(module = "迭代规划", level = "查看")
    public Iteration.View detail(@PathVariable String code) {
        return iterationService.detail(code);
    }

    /** 规划新迭代 */
    @PostMapping
    @RequireProject(module = "迭代规划", level = "编辑")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return iterationService.create(body);
    }

    /** 需求纳入迭代 */
    @PostMapping("/plan")
    @RequireProject(module = "迭代规划", level = "编辑")
    public Map<String, Object> plan(@RequestBody Map<String, Object> body) {
        Object ids = body.get("ids");
        List<String> idList = ids instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        return iterationService.planRequirements(idList,
                body.get("iter") == null ? null : String.valueOf(body.get("iter")));
    }

    /** 编辑迭代 */
    @PutMapping("/{code}")
    @RequireProject(module = "迭代规划", level = "编辑")
    public Map<String, Object> update(@PathVariable String code, @RequestBody Map<String, Object> patch) {
        return iterationService.update(code, patch);
    }

    /** 保存迭代复盘 */
    @PutMapping("/{code}/retro")
    @RequireProject(module = "迭代规划", level = "编辑")
    public Map<String, Object> saveRetro(@PathVariable String code, @RequestBody Map<String, String> body) {
        return iterationService.saveRetro(code, body.get("content"));
    }
}
