package dev.clearflow.stats;

import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.RequireOwner;
import dev.clearflow.common.RequireProject;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 聚合只读端点：概览 / 看板 / 度量 / 我的工作 / 站报 / 全局动态流 */
@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 概览：跨模块聚合，只要求本项目成员（模块级「无」由各业务页菜单/接口各自兜住） */
    @GetMapping("/overview")
    @RequireProject(member = true)
    public Map<String, Object> overview() {
        return statsService.overview();
    }

    /** 看板列数据：与「任务看板」菜单同级 */
    @GetMapping("/board")
    @RequireProject(module = "任务看板", level = "查看")
    public Map<String, Object> board() {
        return statsService.board();
    }

    /** 看板列窗口：每列只返回首页 N 张 + 真实列计数，供前端虚拟滚动按需续取 */
    @GetMapping("/board/columns")
    @RequireProject(module = "任务看板", level = "查看")
    public Map<String, Object> boardColumns(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String prio,
                                            @RequestParam(required = false) String assignee,
                                            @RequestParam(required = false) String iter,
                                            @RequestParam(required = false) String module,
                                            @RequestParam(required = false) Integer limit) {
        return statsService.boardColumns(
                BoardQuery.of(q, status, prio, assignee, iter, module, limit, BoardQuery.DEFAULT_WINDOW));
    }

    /** 看板单列续取（列内滚动到底时调用） */
    @GetMapping("/board/column")
    @RequireProject(module = "任务看板", level = "查看")
    public Map<String, Object> boardColumn(@RequestParam String status,
                                           @RequestParam(defaultValue = "0") int offset,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String prio,
                                           @RequestParam(required = false) String assignee,
                                           @RequestParam(required = false) String iter,
                                           @RequestParam(required = false) String module,
                                           @RequestParam(required = false) Integer limit) {
        return statsService.boardColumnPage(status, offset,
                BoardQuery.of(q, null, prio, assignee, iter, module, limit, BoardQuery.DEFAULT_WINDOW));
    }

    /** 度量报表（迭代燃尽与流速）：仅系统管理员或项目负责人 */
    @GetMapping("/metrics")
    @RequireOwner
    public Map<String, Object> metrics() {
        return statsService.metrics();
    }

    /** 模块质量与产出：按项目模块字典聚合，仅负责人可见（含个人归因，避免扩散排名） */
    @GetMapping("/stats/modules")
    @RequireOwner
    public Map<String, Object> moduleStats(@RequestParam(required = false) String iter) {
        return statsService.moduleStats(iter);
    }

    /** 成员负载与质量：引入缺陷数仅供复盘，不做个人排名口径 */
    @GetMapping("/stats/people")
    @RequireOwner
    public Map<String, Object> peopleStats(@RequestParam(required = false) String iter) {
        return statsService.peopleStats(iter);
    }

    @GetMapping("/mywork")
    @RequireProject(member = true)
    public Map<String, Object> myWork() {
        return statsService.myWork();
    }

    /** 每日站报内容（仅生成文本，不发送） */
    @GetMapping("/mywork/report")
    @RequireProject(member = true)
    public Map<String, Object> report() {
        return statsService.report();
    }

    @GetMapping("/activity/feed")
    @RequireProject(member = true)
    public List<Map<String, Object>> feed(@RequestParam(defaultValue = "20") int limit) {
        return statsService.feed(limit);
    }
}
