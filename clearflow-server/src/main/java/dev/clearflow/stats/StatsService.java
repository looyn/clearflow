package dev.clearflow.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.collab.ActivityMapper;
import dev.clearflow.collab.EntityActivity;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.Days;
import dev.clearflow.common.Times;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.defect.DefectService;
import dev.clearflow.infra.OverviewActivity;
import dev.clearflow.infra.OverviewActivityMapper;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.StatusEvent;
import dev.clearflow.infra.StatusEventService;
import dev.clearflow.iteration.IterationSnapshot;
import dev.clearflow.iteration.IterationSnapshotMapper;
import dev.clearflow.iteration.Iteration;
import dev.clearflow.iteration.IterationMapper;
import dev.clearflow.iteration.Milestone;
import dev.clearflow.iteration.MilestoneMapper;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.module.ProjectModule;
import dev.clearflow.module.ProjectModuleMapper;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.settings.SettingsService;
import dev.clearflow.release.Release;
import dev.clearflow.release.ReleaseMapper;
import dev.clearflow.release.ReleaseService;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.requirement.RequirementService;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import dev.clearflow.task.TaskService;
import dev.clearflow.worklog.Worklog;
import dev.clearflow.worklog.WorklogMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 聚合接口：概览 / 看板 / 度量 / 我的工作 / 站报 / 全局动态流（设计文档 §2.6）
 * GET 聚合接口不分页，行形状与前端 mock 逐字段对齐
 */
@Service
public class StatsService {

    private final TaskMapper taskMapper;
    private final DefectMapper defectMapper;
    private final RequirementMapper requirementMapper;
    private final ReleaseMapper releaseMapper;
    private final IterationMapper iterationMapper;
    private final MilestoneMapper milestoneMapper;
    private final IterationSnapshotMapper snapshotMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final MemberMapper memberMapper;
    private final WorklogMapper worklogMapper;
    private final OverviewActivityMapper overviewActivityMapper;
    private final ActivityMapper activityMapper;
    private final ProjectContext projectContext;
    private final TaskService taskService;
    private final DefectService defectService;
    private final RequirementService requirementService;
    private final ReleaseService releaseService;
    private final SettingsService settings;
    private final ProjectModuleMapper moduleMapper;
    private final StatusEventService statusEvents;

    public StatsService(TaskMapper taskMapper, DefectMapper defectMapper, RequirementMapper requirementMapper,
                        ReleaseMapper releaseMapper, IterationMapper iterationMapper, MilestoneMapper milestoneMapper,
                        IterationSnapshotMapper snapshotMapper, ProjectMemberMapper projectMemberMapper,
                        MemberMapper memberMapper, WorklogMapper worklogMapper,
                        OverviewActivityMapper overviewActivityMapper, ActivityMapper activityMapper,
                        ProjectContext projectContext, TaskService taskService, DefectService defectService,
                        RequirementService requirementService, ReleaseService releaseService, SettingsService settings,
                        ProjectModuleMapper moduleMapper, StatusEventService statusEvents) {
        this.taskMapper = taskMapper;
        this.defectMapper = defectMapper;
        this.requirementMapper = requirementMapper;
        this.releaseMapper = releaseMapper;
        this.iterationMapper = iterationMapper;
        this.milestoneMapper = milestoneMapper;
        this.snapshotMapper = snapshotMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.memberMapper = memberMapper;
        this.worklogMapper = worklogMapper;
        this.overviewActivityMapper = overviewActivityMapper;
        this.activityMapper = activityMapper;
        this.projectContext = projectContext;
        this.taskService = taskService;
        this.defectService = defectService;
        this.requirementService = requirementService;
        this.releaseService = releaseService;
        this.settings = settings;
        this.moduleMapper = moduleMapper;
        this.statusEvents = statusEvents;
    }

    // ===== GET /overview =====

    public Map<String, Object> overview() {
        Long pid = projectContext.currentProjectId();
        LocalDate today = LocalDate.now();
        Iteration active = activeIter(pid);
        // 状态语义统一取项目工作流配置：任务「进行中」= 第二档、终态 = 最后一档；迭代终态同理
        List<String> taskFlow = settings.flow(pid, "columns");
        String taskEnd = taskFlow.isEmpty() ? "" : taskFlow.get(taskFlow.size() - 1);
        String taskDoing = nth(taskFlow, 1);
        List<String> iterFlow = settings.flow(pid, "iterColumns");
        String iterEnd = iterFlow.isEmpty() ? "" : iterFlow.get(iterFlow.size() - 1);

        List<Requirement> reqs = requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>().eq(Requirement::getProjectId, pid));
        List<Defect> defs = defectMapper.selectList(
                new LambdaQueryWrapper<Defect>().eq(Defect::getProjectId, pid));
        List<Iteration> iters = iterationMapper.selectList(
                new LambdaQueryWrapper<Iteration>().eq(Iteration::getProjectId, pid)
                        .orderByDesc(Iteration::getCode));

        // ===== KPI =====
        List<Map<String, Object>> kpis = new ArrayList<>();

        // ① 需求吞吐：滚动 7 天创建 vs 前 7 天
        long thisWeek = reqs.stream().filter(r -> inDays(r.getCreated(), today.minusDays(6), today)).count();
        long lastWeek = reqs.stream()
                .filter(r -> inDays(r.getCreated(), today.minusDays(13), today.minusDays(7))).count();
        Map<String, Object> kpi1 = new LinkedHashMap<>();
        kpi1.put("label", "需求吞吐（本周）");
        kpi1.put("num", String.valueOf(thisWeek));
        if (lastWeek > 0) {
            long pct = Math.round((thisWeek - lastWeek) * 100.0 / lastWeek);
            kpi1.put("trend", Map.of("dir", pct >= 0 ? "up" : "down",
                    "text", (pct >= 0 ? "↑ " : "↓ ") + Math.abs(pct) + "%",
                    "good", pct >= 0));
        }
        kpi1.put("note", "较上周");
        kpis.add(kpi1);

        // ② 迭代完成度
        Map<String, Object> kpi2 = new LinkedHashMap<>();
        kpi2.put("label", "迭代完成度");
        if (active != null) {
            int pct = nz(active.getPct()), done = nz(active.getDone()), total = nz(active.getTotal());
            kpi2.put("num", String.valueOf(pct));
            kpi2.put("unit", "%");
            kpi2.put("note", done + " / " + total + " 项任务已关闭");
            kpi2.put("bar", pct);
        } else {
            kpi2.put("num", "0");
            kpi2.put("unit", "%");
            kpi2.put("note", "暂无进行中迭代");
            kpi2.put("bar", 0);
        }
        kpis.add(kpi2);

        // ③ 缺陷存量（P0/P1 未到终态）
        String defectTerminal = defectService.terminalStatus(pid);
        long p0 = defs.stream().filter(d -> !defectTerminal.equals(d.getStatus()) && "p0".equals(d.getPrio())).count();
        long p1 = defs.stream().filter(d -> !defectTerminal.equals(d.getStatus()) && "p1".equals(d.getPrio())).count();
        Map<String, Object> kpi3 = new LinkedHashMap<>();
        kpi3.put("label", "缺陷存量（P0 / P1）");
        kpi3.put("num", String.valueOf(p0 + p1));
        kpi3.put("note", "P0 " + p0 + " · P1 " + p1 + " · 未关闭");
        kpis.add(kpi3);

        // ④ 按时交付率：近四个终态（默认已发布）迭代 pct 均值
        Map<String, Object> kpi4 = new LinkedHashMap<>();
        kpi4.put("label", "按时交付率");
        List<Integer> doneIters = iters.stream().filter(i -> iterEnd.equals(i.getState()))
                .map(Iteration::getPct).filter(java.util.Objects::nonNull).limit(4).toList();
        if (doneIters.isEmpty()) {
            kpi4.put("num", "—");
            kpi4.put("unit", "");
            kpi4.put("note", "暂无已发布迭代");
        } else {
            double avg = doneIters.stream().mapToInt(Integer::intValue).average().orElse(0);
            kpi4.put("num", fmt(avg));
            kpi4.put("unit", "%");
            kpi4.put("note", "近四迭代均值 · 目标 90%");
        }
        kpis.add(kpi4);

        // ===== 当前迭代面板 =====
        Map<String, Object> ci = new LinkedHashMap<>();
        if (active != null) {
            int total = Math.max(1, nz(active.getTotal()));
            int doneVal = Math.min(nz(active.getDone()), total);
            int remain = total - doneVal;
            List<Task> iterTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                    .eq(Task::getProjectId, pid).eq(Task::getIter, active.getCode()));
            long doingCnt = iterTasks.stream().filter(t -> taskDoing.equals(t.getStatus())).count();
            long todoCnt = iterTasks.stream().filter(t -> !taskEnd.equals(t.getStatus())
                    && !taskDoing.equals(t.getStatus())).count();
            long split = doingCnt + todoCnt;
            int doingVal = split == 0 ? 0 : (int) Math.round(remain * (double) doingCnt / split);
            int todoVal = remain - doingVal;
            int donePct = (int) Math.round(doneVal * 100.0 / total);
            int doingPct = (int) Math.round(doingVal * 100.0 / total);
            int todoPct = Math.max(0, 100 - donePct - doingPct);

            ci.put("name", "当前迭代 · " + active.getName());
            ci.put("state", settings.statusName(pid, "iterColumns", active.getState()));
            ci.put("release", releaseLabel(active.getIterRange()));
            ci.put("bar", List.of(
                    Map.of("cls", "b-done", "pct", donePct),
                    Map.of("cls", "b-doing", "pct", doingPct),
                    Map.of("cls", "b-todo", "pct", todoPct)));
            ci.put("legend", List.of(
                    Map.of("color", "var(--primary)", "label", "已完成 " + doneVal),
                    Map.of("color", "var(--primary-light)", "label", "进行中 " + doingVal),
                    Map.of("color", "#D8DBE0", "label", "未开始 " + todoVal)));
            ci.put("total", nz(active.getTotal()));
            long linkReq = reqs.stream().filter(r -> active.getCode().equals(r.getIter())).count();
            long newDef = defs.stream().filter(d -> active.getCode().equals(d.getIter())).count();
            String defectEnd = defectService.terminalStatus(pid);
            long fixedDef = defs.stream().filter(d -> active.getCode().equals(d.getIter())
                    && defectEnd.equals(d.getStatus())).count();
            ci.put("stats", List.of(
                    Map.of("v", String.valueOf(linkReq), "k", "关联需求"),
                    Map.of("v", String.valueOf(newDef), "k", "新增缺陷"),
                    Map.of("v", donePct + "%", "k", "任务完成率"),
                    Map.of("v", String.valueOf(fixedDef), "k", "修复缺陷")));
        } else {
            ci.put("name", "当前迭代 · 暂无进行中迭代");
            ci.put("state", settings.statusName(pid, "iterColumns", iterFlow.isEmpty() ? "" : iterFlow.get(0)));
            ci.put("release", "—");
            ci.put("bar", List.of(
                    Map.of("cls", "b-done", "pct", 0),
                    Map.of("cls", "b-doing", "pct", 0),
                    Map.of("cls", "b-todo", "pct", 100)));
            ci.put("legend", List.of(
                    Map.of("color", "var(--primary)", "label", "已完成 0"),
                    Map.of("color", "var(--primary-light)", "label", "进行中 0"),
                    Map.of("color", "#D8DBE0", "label", "未开始 0")));
            ci.put("total", 0);
            ci.put("stats", List.of(
                    Map.of("v", "0", "k", "关联需求"),
                    Map.of("v", "0", "k", "新增缺陷"),
                    Map.of("v", "—", "k", "任务完成率"),
                    Map.of("v", "0", "k", "修复缺陷")));
        }
        List<Map<String, Object>> miles = new ArrayList<>();
        if (active != null) {
            milestoneMapper.selectList(new LambdaQueryWrapper<Milestone>()
                            .eq(Milestone::getProjectId, pid)
                            .eq(Milestone::getIterationCode, active.getCode())
                            .orderByAsc(Milestone::getMsDate))
                    .forEach(m -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("d", m.getMsDate());
                        row.put("n", m.getName());
                        row.put("state", m.getState() == null ? "" : m.getState());
                        miles.add(row);
                    });
        }
        ci.put("miles", miles);

        // ===== 团队负载（窗口工时 ÷ capacity）=====
        List<LocalDate> window = Days.window(active == null ? null : active.getIterRange());
        String wStart = window.get(0).toString();
        String wEndNext = window.get(window.size() - 1).plusDays(1).toString();
        Map<Long, Double> hours = new HashMap<>();
        worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                        .eq(Worklog::getProjectId, pid)
                        .ge(Worklog::getWorkDate, wStart)
                        .lt(Worklog::getWorkDate, wEndNext)
                        .select(Worklog::getMemberId, Worklog::getHours))
                .forEach(w -> hours.merge(w.getMemberId(),
                        w.getHours() == null ? 0 : w.getHours(), Double::sum));
        List<Map<String, Object>> teamLoad = new ArrayList<>();
        for (ProjectMember pm : projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, pid).orderByAsc(ProjectMember::getMemberId))) {
            Member m = memberMapper.selectById(pm.getMemberId());
            if (m == null) continue;
            int cap = m.getCapacity() == null || m.getCapacity() <= 0 ? 100 : m.getCapacity();
            double h = hours.getOrDefault(m.getId(), 0.0);
            int pct = (int) Math.round(h * 100 / cap);
            teamLoad.add(Map.of("id", m.getId(), "name", m.getName(),
                    "role", m.getRole() == null ? "" : m.getRole(), "pct", pct,
                    "cls", h > cap ? "over" : h >= cap * 0.8 ? "warm" : "cool"));
        }

        // ===== 风险与阻塞（从真实数据推导：逾期任务 / 未关闭 P0·P1 / 成员超载） =====
        List<Map<String, Object>> risks = new ArrayList<>();
        List<Task> allTasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().eq(Task::getProjectId, pid));
        String todayStr = today.toString();
        List<Task> overdue = allTasks.stream()
                .filter(t -> !taskEnd.equals(t.getStatus()) && t.getDue() != null
                        && t.getDue().length() >= 10
                        && t.getDue().substring(0, 10).compareTo(todayStr) < 0)
                .toList();
        if (!overdue.isEmpty()) {
            String minDue = overdue.stream().map(t -> t.getDue().substring(0, 10))
                    .min(String::compareTo).orElse(todayStr);
            risks.add(Map.of("level", "r", "tag", "风险",
                    "title", overdue.size() + " 项任务已逾期",
                    "meta", "最早截止 " + minDue + " · 建议优先处理或调整排期"));
        }
        if (p0 + p1 > 0) {
            risks.add(Map.of("level", "b", "tag", "阻塞",
                    "title", (p0 + p1) + " 个 P0/P1 缺陷未关闭",
                    "meta", "P0 " + p0 + " · P1 " + p1 + " · 影响发布质量"));
        }
        long overloaded = teamLoad.stream().filter(p -> ((Number) p.get("pct")).intValue() > 100).count();
        if (overloaded > 0) {
            risks.add(Map.of("level", "n", "tag", "提示",
                    "title", overloaded + " 名成员本迭代负载超限",
                    "meta", "在办任务估时超出容量 · 建议分摊或顺延"));
        }

        List<Map<String, Object>> activities = new ArrayList<>();
        overviewActivityMapper.selectList(new LambdaQueryWrapper<OverviewActivity>()
                        .orderByDesc(OverviewActivity::getCreatedAt)
                        .orderByDesc(OverviewActivity::getId)
                        .last("LIMIT 8"))
                .forEach(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("time", Times.format(a.getCreatedAt()));
                    row.put("html", a.getHtml());
                    activities.add(row);
                });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("currentIter", ci);
        out.put("teamLoad", teamLoad);
        out.put("risks", risks);
        out.put("activities", activities);
        return out;
    }

    // ===== GET /board =====

    public Map<String, Object> board() {
        Long pid = projectContext.currentProjectId();

        LambdaQueryWrapper<Task> qw = new LambdaQueryWrapper<Task>().eq(Task::getProjectId, pid);
        List<Task> tasks = taskMapper.selectList(qw.orderByAsc(Task::getSort).orderByAsc(Task::getId));

        // 看板列 = 项目工作流配置（columns）：列头取配置名，列内即该状态的全部任务
        List<String> flow = settings.flow(pid, "columns");
        String first = flow.isEmpty() ? "" : flow.get(0);

        Map<String, List<Task>> grouped = new HashMap<>();
        tasks.forEach(t -> grouped.computeIfAbsent(t.getStatus() == null ? first : t.getStatus(),
                k -> new ArrayList<>()).add(t));

        List<Map<String, Object>> columns = new ArrayList<>();
        for (String key : flow) {
            columns.add(boardColumn(key, settings.statusName(pid, "columns", key), grouped.get(key)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        return out;
    }

    private Map<String, Object> boardColumn(String key, String name, List<Task> tasks) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("key", key);
        col.put("name", name);
        col.put("tasks", taskService.views(tasks == null ? List.of() : tasks));
        return col;
    }

    // ===== GET /board/columns + /board/column：看板列窗口（前端虚拟滚动用）=====

    /** 每列只取首页窗口，列计数走一次 GROUP BY；卡片装配仍是一次批量 IN */
    public Map<String, Object> boardColumns(BoardQuery bq) {
        Long pid = projectContext.currentProjectId();
        List<String> flow = settings.flow(pid, "columns");
        Map<String, Long> counts = taskService.statusCounts(bq);

        Map<String, List<Task>> rows = new LinkedHashMap<>();
        List<Task> all = new ArrayList<>();
        for (String key : flow) {
            if (counts.getOrDefault(key, 0L) <= 0) {
                rows.put(key, List.of());
                continue;
            }
            List<Task> part = taskService.columnPage(key, 0, bq);
            rows.put(key, part);
            all.addAll(part);
        }
        Map<String, Task.View> byId = new HashMap<>();
        taskService.views(all).forEach(v -> byId.put(v.id(), v));

        List<Map<String, Object>> columns = new ArrayList<>();
        for (String key : flow) {
            long total = counts.getOrDefault(key, 0L);
            columns.add(boardWindow(key, settings.statusName(pid, "columns", key),
                    rows.getOrDefault(key, List.of()), byId, total));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("limit", bq.limit());
        return out;
    }

    /** 列内续取：滚动到底按 offset 拉下一窗，total 用同筛选下的 GROUP BY 计数 */
    public Map<String, Object> boardColumnPage(String status, int offset, BoardQuery bq) {
        List<Task> rows = taskService.columnPage(status, offset, bq);
        Map<String, Task.View> byId = new HashMap<>();
        taskService.views(rows).forEach(v -> byId.put(v.id(), v));
        long total = taskService.statusCounts(bq).getOrDefault(status, 0L);
        int loaded = Math.max(0, offset) + rows.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", status);
        out.put("total", total);
        out.put("items", rows.stream().map(t -> byId.get(t.getId())).filter(java.util.Objects::nonNull).toList());
        out.put("hasMore", loaded < total);
        out.put("next", loaded);
        return out;
    }

    private Map<String, Object> boardWindow(String key, String name, List<Task> rows,
                                            Map<String, Task.View> byId, long total) {
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("key", key);
        col.put("name", name);
        col.put("total", total);
        col.put("items", rows.stream().map(t -> byId.get(t.getId())).filter(java.util.Objects::nonNull).toList());
        col.put("hasMore", rows.size() < total);
        col.put("next", rows.size());
        return col;
    }

    // ===== GET /metrics =====

    public Map<String, Object> metrics() {
        Long pid = projectContext.currentProjectId();
        LocalDate today = LocalDate.now();
        Iteration active = activeIter(pid);

        List<Task> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<Task>().eq(Task::getProjectId, pid));

        // ===== 燃尽（口径：迭代内需求点数，与迭代快照/迭代规划页燃保持一致）=====
        List<LocalDate> days = active == null ? Days.window(null) : Days.window(active.getIterRange());
        if (days.size() < 2) {
            days = Days.window(null);
        }
        String reqEnd = settings.terminalStatus(pid, "reqColumns");
        List<Requirement> iterReqs = active == null ? List.of() : requirementMapper.selectList(
                new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, pid)
                        .eq(Requirement::getIter, active.getCode()));
        int total = iterReqs.stream().mapToInt(r -> r.getEstimate() == null ? 0 : r.getEstimate()).sum();
        if (total < 1 && active != null) {
            total = nz(active.getTotal());
        }
        total = Math.max(1, total);
        int remainingToday = Math.max(0, total - iterReqs.stream()
                .filter(r -> reqEnd.equals(r.getStatus()))
                .mapToInt(r -> r.getEstimate() == null ? 0 : r.getEstimate()).sum());

        int todayIdx = 0;
        for (int i = 0; i < days.size(); i++) {
            if (!days.get(i).isAfter(today)) todayIdx = i;
        }

        Map<String, Integer> snaps = new HashMap<>();
        if (active != null) {
            snapshotMapper.selectList(new LambdaQueryWrapper<IterationSnapshot>()
                            .eq(IterationSnapshot::getProjectId, pid)
                            .eq(IterationSnapshot::getIterationCode, active.getCode()))
                    .forEach(s -> snaps.put(s.getSnapDate(), s.getRemaining()));
        }
        List<Integer> actual = new ArrayList<>();
        int last = total;
        for (int i = 0; i <= todayIdx; i++) {
            Integer v = snaps.get(days.get(i).toString());
            if (v != null) last = v;
            actual.add(last);
        }
        if (!actual.isEmpty()) {
            // 今天按需求现状实时计算，不等次日快照
            actual.set(actual.size() - 1, remainingToday);
        }

        String bdNote;
        if (active == null) {
            bdNote = "暂无进行中迭代";
        } else {
            int remaining = actual.get(actual.size() - 1);
            int ideal = (int) Math.round(total * (1 - (double) todayIdx / (days.size() - 1)));
            int diff = remaining - ideal;
            String pace = diff > 0 ? "比理想多 " + diff + " 点 · 需加快进度"
                    : diff < 0 ? "比理想少 " + (-diff) + " 点 · 进度正常"
                    : "与理想持平 · 进度正常";
            bdNote = "当前剩余 " + remaining + " 点 · " + pace;
        }
        Map<String, Object> burndown = new LinkedHashMap<>();
        burndown.put("iter", active == null ? "" : active.getCode());
        burndown.put("days", days.stream().map(LocalDate::toString).toList());
        burndown.put("total", total);
        burndown.put("actual", actual);
        burndown.put("todayIndex", todayIdx);
        burndown.put("note", bdNote);

        // ===== 流速 =====
        List<Map<String, Object>> velocity = new ArrayList<>();
        iterationMapper.selectList(new LambdaQueryWrapper<Iteration>()
                        .eq(Iteration::getProjectId, pid).orderByAsc(Iteration::getCode))
                .forEach(it -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("iter", it.getCode());
                    v.put("committed", nz(it.getTotal()));
                    v.put("completed", nz(it.getDone()));
                    velocity.add(v);
                });

        // ===== 周期指标 =====
        // 活动文本由 CollabService.changes 按配置名生成，因此后缀也用配置名匹配
        List<String> taskFlow = settings.flow(pid, "columns");
        List<String> defFlow = settings.defectFlow(pid);
        Map<String, String> toPublished = statusTime(pid, "requirement",
                settings.statusName(pid, "reqColumns", reqEnd));
        Map<String, String> toReview = statusTime(pid, "task",
                settings.statusName(pid, "columns", nth(taskFlow, 2)));
        Map<String, String> toFixed = statusTime(pid, "defect",
                defectService.statusName(pid, nth(defFlow, 2)));

        // 需求交付周期（终态需求：创建 → 发布；completed_at 优先）
        List<Double> reqCycles = new ArrayList<>();
        requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                        .eq(Requirement::getProjectId, pid).eq(Requirement::getStatus, reqEnd))
                .forEach(r -> {
                    LocalDate c = Days.parse(r.getCreated());
                    String t = r.getCompletedAt() != null ? Times.format(r.getCompletedAt())
                            : toPublished.get(r.getId());
                    if (c != null && t != null) {
                        long d = ChronoUnit.DAYS.between(c, LocalDate.parse(t.substring(0, 10)));
                        reqCycles.add((double) Math.max(0, d));
                    }
                });

        // 任务平均流转（终态：创建 → 关闭；completed_at 优先）
        List<Double> taskCycles = new ArrayList<>();
        String taskEnd = taskFlow.isEmpty() ? "" : taskFlow.get(taskFlow.size() - 1);
        tasks.stream().filter(t -> taskEnd.equals(t.getStatus())).forEach(t -> {
            LocalDate c = Days.parse(t.getCreated());
            LocalDate u = t.getCompletedAt() != null ? t.getCompletedAt().toLocalDate() : Days.parse(t.getUpdated());
            if (c != null && u != null) {
                taskCycles.add((double) Math.max(0, ChronoUnit.DAYS.between(c, u)));
            }
        });

        // 评审平均时长（review 档：进入待验收 → 现在，小时）
        List<Double> reviewHours = new ArrayList<>();
        String reviewKey = nth(taskFlow, 2);
        tasks.stream().filter(t -> reviewKey.equals(t.getStatus())).forEach(t -> {
            String rt = toReview.get(t.getId());
            if (rt != null) {
                LocalDateTime rdt = parseDt(rt);
                if (rdt != null) {
                    reviewHours.add((double) Math.max(0, Duration.between(rdt, LocalDateTime.now()).toHours()));
                }
            }
        });

        // 缺陷修复时长（发现 → 已修复；结构化时间戳优先）
        List<Double> defCycles = new ArrayList<>();
        defectMapper.selectList(new LambdaQueryWrapper<Defect>().eq(Defect::getProjectId, pid))
                .forEach(d -> {
                    String ft = toFixed.get(d.getId());
                    LocalDate found = Days.parse(d.getDate());
                    LocalDate fixed = d.getResolvedAt() != null ? d.getResolvedAt().toLocalDate()
                            : (ft == null ? null : LocalDate.parse(ft.substring(0, 10)));
                    if (fixed != null && found != null) {
                        defCycles.add((double) Math.max(0, ChronoUnit.DAYS.between(found, fixed)));
                    }
                });

        List<Map<String, Object>> cycle = List.of(
                cycleRow("需求交付周期", avg(reqCycles), " 天", "近四迭代均值", "数据不足"),
                cycleRow("任务平均流转", avg(taskCycles), " 天", "创建 → 关闭", "创建 → 关闭"),
                cycleRow("评审平均时长", avg(reviewHours), " 小时", "在评任务均值", "在评任务均值"),
                cycleRow("缺陷修复时长", avg(defCycles), " 天", "发现 → 已修复", "发现 → 已修复"));

        // ===== 吞吐（近 4 个 ISO 周，done 任务 updated 落周计数）=====
        LocalDate monday = Days.monday();
        List<String> weeks = new ArrayList<>();
        List<Integer> closed = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            LocalDate start = monday.minusDays(7L * i);
            LocalDate end = start.plusDays(6);
            weeks.add("W" + start.get(WeekFields.ISO.weekOfWeekBasedYear()));
                    closed.add((int) tasks.stream()
                    .filter(t -> taskEnd.equals(t.getStatus()))
                    .filter(t -> {
                        LocalDate u = t.getCompletedAt() != null ? t.getCompletedAt().toLocalDate() : Days.parse(t.getUpdated());
                        return u != null && !u.isBefore(start) && !u.isAfter(end);
                    }).count());
        }

        // ===== DORA =====
        // 「已发布 + 已回滚」算已部署：终态取配置最后一档，rolled 为发版特有状态
        List<String> relFlow = settings.flow(pid, "relColumns");
        String relEnd = relFlow.isEmpty() ? "" : relFlow.get(relFlow.size() - 1);
        List<Release> rels = releaseMapper.selectList(
                new LambdaQueryWrapper<Release>().eq(Release::getProjectId, pid));
        List<Release> deployed = rels.stream()
                .filter(r -> relEnd.equals(r.getStatus()) || "rolled".equals(r.getStatus())).toList();
        long rolled = deployed.stream().filter(r -> "rolled".equals(r.getStatus())).count();
        long recent = deployed.stream()
                .filter(r -> inDays(r.getDate(), today.minusDays(13), today)).count();

        List<Double> leadHours = new ArrayList<>();
        deployed.forEach(r -> {
            LocalDateTime created = parseDt(r.getCreated());
            if (created != null && Days.parse(r.getDate()) != null) {
                long mins = Duration.between(created, Days.parse(r.getDate()).atStartOfDay()).toMinutes();
                leadHours.add((double) Math.max(0, mins) / 60);
            }
        });
        List<Double> recoverMins = new ArrayList<>();
        rels.stream().filter(r -> "rolled".equals(r.getStatus())).forEach(r -> {
            LocalDateTime c = parseDt(r.getCreated());
            LocalDateTime u = parseDt(r.getUpdated());
            if (c != null && u != null) {
                recoverMins.add((double) Math.max(0, Duration.between(c, u).toMinutes()));
            }
        });

        List<Map<String, Object>> dora = new ArrayList<>();
        dora.add(Map.of("label", "部署频率", "value", fmt(recent / 14.0), "unit", "次/天"));
        dora.add(avgLead(leadHours));
        dora.add(Map.of("label", "变更失败率",
                "value", deployed.isEmpty() ? "—"
                        : fmt(rolled * 100.0 / deployed.size()),
                "unit", deployed.isEmpty() ? "" : "%"));
        dora.add(Map.of("label", "故障恢复时间",
                "value", recoverMins.isEmpty() ? "—"
                        : String.valueOf(Math.round(recoverMins.stream().mapToDouble(Double::doubleValue).average().orElse(0))),
                "unit", recoverMins.isEmpty() ? "" : "分钟"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("burndown", burndown);
        out.put("velocity", velocity);
        out.put("cycle", cycle);
        out.put("throughput", Map.of("weeks", weeks, "closed", closed));
        out.put("dora", dora);
        return out;
    }

    // ===== GET /stats/modules =====

    /**
     * 模块维度报表（仅项目负责人/管理员）：
     * 需求量与点数、任务完成与投入、缺陷密度（每 100 需求点）、生产逃逸、重开、MTTR、工时。
     * 口径全部来自真实数据；moduleId 为空的行归入「未归类」，避免悄悄漏掉。
     */
    public Map<String, Object> moduleStats(String iter) {
        Long pid = projectContext.currentProjectId();
        String it = iter == null || iter.isBlank() ? null : iter.trim();

        List<ProjectModule> modules = moduleMapper.selectList(new LambdaQueryWrapper<ProjectModule>()
                .eq(ProjectModule::getProjectId, pid)
                .orderByAsc(ProjectModule::getSort).orderByAsc(ProjectModule::getId));
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .select(Requirement::getId, Requirement::getModuleId, Requirement::getEstimate,
                        Requirement::getStatus, Requirement::getIter)
                .eq(Requirement::getProjectId, pid)
                .eq(it != null, Requirement::getIter, it));
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .select(Task::getId, Task::getModuleId, Task::getStatus, Task::getEstimate,
                        Task::getSpent, Task::getIter)
                .eq(Task::getProjectId, pid)
                .eq(it != null, Task::getIter, it));
        List<Defect> defects = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                .select(Defect::getId, Defect::getModuleId, Defect::getStatus, Defect::getPrio,
                        Defect::getEnv, Defect::getDate, Defect::getIter, Defect::getReopenCount,
                        Defect::getResolvedAt)
                .eq(Defect::getProjectId, pid)
                .eq(it != null, Defect::getIter, it));
        List<Worklog> logs = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .select(Worklog::getRefKind, Worklog::getRefId, Worklog::getHours)
                .eq(Worklog::getProjectId, pid));

        Map<Long, ModAgg> aggs = new LinkedHashMap<>();
        modules.forEach(m -> aggs.put(m.getId(), new ModAgg()));

        String reqEnd = settings.terminalStatus(pid, "reqColumns");
        String taskEnd = settings.terminalStatus(pid, "columns");
        List<String> defFlow = settings.defectFlow(pid);
        String defEnd = defFlow.isEmpty() ? "" : defFlow.get(defFlow.size() - 1);

        for (Requirement r : reqs) {
            ModAgg a = modAgg(aggs, r.getModuleId());
            a.reqCount++;
            a.reqPoints += r.getEstimate() == null ? 0 : r.getEstimate();
            if (reqEnd.equals(r.getStatus())) a.reqDone++;
        }
        for (Task t : tasks) {
            ModAgg a = modAgg(aggs, t.getModuleId());
            a.taskCount++;
            a.taskEstimate += t.getEstimate() == null ? 0 : t.getEstimate();
            a.taskSpent += t.getSpent() == null ? 0 : t.getSpent();
            if (taskEnd.equals(t.getStatus())) a.taskDone++;
        }
        for (Defect d : defects) {
            ModAgg a = modAgg(aggs, d.getModuleId());
            a.defectCount++;
            if (!defEnd.equals(d.getStatus())) {
                a.openDefects++;
                if ("p0".equals(d.getPrio()) || "p1".equals(d.getPrio())) a.p0p1Open++;
            }
            if (isProductionEnv(d.getEnv())) a.escape++;
            if (d.getReopenCount() != null && d.getReopenCount() > 0) a.reopen++;
            LocalDate found = Days.parse(d.getDate());
            if (d.getResolvedAt() != null && found != null) {
                double hours = Duration.between(found.atStartOfDay(), d.getResolvedAt()).toMinutes() / 60.0;
                a.mttrSum += Math.max(0, hours);
                a.mttrN++;
            }
        }

        // 工时按关联实体回落到模块；没有关联实体（其他）不计入模块口径
        Map<String, Long> taskModule = new HashMap<>();
        tasks.forEach(t -> taskModule.put(t.getId(), t.getModuleId() == null ? 0L : t.getModuleId()));
        Map<String, Long> defectModule = new HashMap<>();
        defects.forEach(d -> defectModule.put(d.getId(), d.getModuleId() == null ? 0L : d.getModuleId()));
        for (Worklog w : logs) {
            Long mid = null;
            if ("task".equals(w.getRefKind())) {
                mid = taskModule.get(w.getRefId());
            } else if ("defect".equals(w.getRefKind())) {
                mid = defectModule.get(w.getRefId());
            }
            if (mid != null && w.getHours() != null) {
                modAgg(aggs, mid).worklog += w.getHours();
            }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (ProjectModule m : modules) {
            ModAgg a = aggs.get(m.getId());
            items.add(moduleRow(m.getId(), m.getName(), m.getOwner(), m.getDescription(), a));
        }
        if (aggs.containsKey(0L)) {
            items.add(moduleRow(null, "未归类", null, "尚未指定业务模块的需求 / 任务 / 缺陷", aggs.get(0L)));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iter", it == null ? "" : it);
        out.put("items", items);
        out.put("note", "缺陷密度 = 缺陷数 / 需求点数 × 100；逃逸率 = 生产环境缺陷 / 全部缺陷；MTTR = 发现 → 修复平均小时数");
        return out;
    }

    private Map<String, Object> moduleRow(Long id, String name, Long owner, String description, ModAgg a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", name);
        row.put("owner", owner);
        row.put("ownerName", owner == null ? "" : memberName(owner));
        row.put("description", description == null ? "" : description);
        row.put("reqCount", a.reqCount);
        row.put("reqPoints", a.reqPoints);
        row.put("reqDone", a.reqDone);
        row.put("reqDoneRate", pct(a.reqDone, a.reqCount));
        row.put("taskCount", a.taskCount);
        row.put("taskDone", a.taskDone);
        row.put("taskEstimate", a.taskEstimate);
        row.put("taskSpent", round1(a.taskSpent));
        row.put("taskDoneRate", pct(a.taskDone, a.taskCount));
        row.put("defectCount", a.defectCount);
        row.put("openDefects", a.openDefects);
        row.put("p0p1Open", a.p0p1Open);
        row.put("defectDensity", a.reqPoints > 0 ? round1(a.defectCount * 100.0 / a.reqPoints) : null);
        row.put("mttrHours", a.mttrN == 0 ? null : round1(a.mttrSum / a.mttrN));
        row.put("escapeCount", a.escape);
        row.put("escapeRate", pct(a.escape, a.defectCount));
        row.put("reopenCount", a.reopen);
        row.put("reopenRate", pct(a.reopen, a.defectCount));
        row.put("worklogHours", round1(a.worklog));
        return row;
    }

    private ModAgg modAgg(Map<Long, ModAgg> aggs, Long moduleId) {
        return aggs.computeIfAbsent(moduleId == null ? 0L : moduleId, k -> new ModAgg());
    }

    private static final class ModAgg {
        long reqCount;
        long reqPoints;
        long reqDone;
        long taskCount;
        long taskDone;
        long taskEstimate;
        double taskSpent;
        long defectCount;
        long openDefects;
        long p0p1Open;
        long escape;
        long reopen;
        double mttrSum;
        int mttrN;
        double worklog;
    }

    // ===== GET /stats/people =====

    /**
     * 成员维度报表（仅项目负责人/管理员）：
     * 任务吞吐/WIP/逾期、缺陷修复量与平均修复时长、工时。
     * 「引入缺陷」只用于复盘定位系统性问题，界面上明确标注不建议做个人排名；
     * 报告人 ≠ 引入人，两者分开统计。
     */
    public Map<String, Object> peopleStats(String iter) {
        Long pid = projectContext.currentProjectId();
        String it = iter == null || iter.isBlank() ? null : iter.trim();

        List<ProjectMember> pms = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, pid));
        List<Long> memberIds = pms.stream().map(ProjectMember::getMemberId).toList();
        List<Member> members = memberIds.isEmpty() ? List.of() : memberMapper.selectBatchIds(memberIds);

        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .select(Requirement::getId, Requirement::getOwner, Requirement::getStatus)
                .eq(Requirement::getProjectId, pid)
                .eq(it != null, Requirement::getIter, it));
        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .select(Task::getId, Task::getAssignee, Task::getStatus, Task::getEstimate, Task::getDue)
                .eq(Task::getProjectId, pid)
                .eq(it != null, Task::getIter, it));
        List<Defect> defects = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                .select(Defect::getId, Defect::getOwner, Defect::getReporterId, Defect::getIntroducedBy,
                        Defect::getStatus, Defect::getDate, Defect::getResolvedAt)
                .eq(Defect::getProjectId, pid)
                .eq(it != null, Defect::getIter, it));
        List<Worklog> logs = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .select(Worklog::getMemberId, Worklog::getRefKind, Worklog::getRefId, Worklog::getHours)
                .eq(Worklog::getProjectId, pid));

        Map<Long, PersonAgg> aggs = new HashMap<>();
        members.forEach(m -> aggs.put(m.getId(), new PersonAgg()));

        String reqEnd = settings.terminalStatus(pid, "reqColumns");
        List<String> taskFlow = settings.flow(pid, "columns");
        String taskEnd = settings.terminalStatus(pid, "columns");
        String wipKey = nth(taskFlow, 1);
        List<String> defFlow = settings.defectFlow(pid);
        String defEnd = defFlow.isEmpty() ? "" : defFlow.get(defFlow.size() - 1);
        int fixedIdx = Math.min(2, Math.max(0, defFlow.size() - 1));
        LocalDate today = LocalDate.now();

        for (Requirement r : reqs) {
            PersonAgg a = aggs.get(r.getOwner());
            if (a != null && !reqEnd.equals(r.getStatus())) a.openRequirements++;
        }
        for (Task t : tasks) {
            PersonAgg a = aggs.get(t.getAssignee());
            if (a == null) continue;
            boolean done = taskEnd.equals(t.getStatus());
            if (done) {
                a.doneTasks++;
            } else {
                a.openTasks++;
                a.openEstimate += t.getEstimate() == null ? 0 : t.getEstimate();
                if (wipKey.equals(t.getStatus())) a.wip++;
                LocalDate due = Days.parse(t.getDue());
                if (due != null && due.isBefore(today)) a.overdue++;
            }
        }
        for (Defect d : defects) {
            PersonAgg owner = aggs.get(d.getOwner());
            if (owner != null) {
                owner.ownerDefects++;
                if (!defEnd.equals(d.getStatus())) owner.openDefects++;
                LocalDate found = Days.parse(d.getDate());
                int idx = defFlow.indexOf(d.getStatus());
                if (idx >= fixedIdx && idx >= 0) owner.fixedDefects++;
                if (d.getResolvedAt() != null && found != null) {
                    double hours = Duration.between(found.atStartOfDay(), d.getResolvedAt()).toMinutes() / 60.0;
                    owner.fixSum += Math.max(0, hours);
                    owner.fixN++;
                }
            }
            PersonAgg intro = aggs.get(d.getIntroducedBy());
            if (intro != null) intro.introducedDefects++;
            PersonAgg reporter = aggs.get(d.getReporterId());
            if (reporter != null) reporter.reportedDefects++;
        }

        // 迭代筛选时，工时只累计关联到本迭代任务/缺陷的记录；「其他」无法归属迭代
        java.util.Set<String> iterTaskIds = new java.util.HashSet<>();
        tasks.forEach(t -> iterTaskIds.add(t.getId()));
        java.util.Set<String> iterDefectIds = new java.util.HashSet<>();
        defects.forEach(d -> iterDefectIds.add(d.getId()));
        for (Worklog w : logs) {
            PersonAgg a = aggs.get(w.getMemberId());
            if (a == null || w.getHours() == null) continue;
            if (it != null) {
                boolean inIter = ("task".equals(w.getRefKind()) && iterTaskIds.contains(w.getRefId()))
                        || ("defect".equals(w.getRefKind()) && iterDefectIds.contains(w.getRefId()));
                if (!inIter) continue;
            }
            a.worklog += w.getHours();
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Member m : members) {
            PersonAgg a = aggs.get(m.getId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("name", m.getName());
            row.put("role", m.getRole() == null ? "" : m.getRole());
            row.put("openRequirements", a.openRequirements);
            row.put("openTasks", a.openTasks);
            row.put("doneTasks", a.doneTasks);
            row.put("openEstimate", a.openEstimate);
            row.put("wip", a.wip);
            row.put("overdue", a.overdue);
            row.put("completionRate", pct(a.doneTasks, a.doneTasks + a.openTasks));
            row.put("ownerDefects", a.ownerDefects);
            row.put("openDefects", a.openDefects);
            row.put("fixedDefects", a.fixedDefects);
            row.put("avgFixHours", a.fixN == 0 ? null : round1(a.fixSum / a.fixN));
            row.put("introducedDefects", a.introducedDefects);
            row.put("reportedDefects", a.reportedDefects);
            row.put("worklogHours", round1(a.worklog));
            items.add(row);
        }
        items.sort(Comparator
                .comparing((Map<String, Object> r) -> ((Number) r.get("openTasks")).longValue()).reversed()
                .thenComparing(r -> ((Number) r.get("worklogHours")).doubleValue(), Comparator.reverseOrder()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("iter", it == null ? "" : it);
        out.put("items", items);
        out.put("note", "完成率 = 已完成 / (已完成 + 未完成)；引入缺陷仅用于复盘定位，不建议作为个人排名；报告人 ≠ 引入人");
        return out;
    }

    private static final class PersonAgg {
        long openRequirements;
        long openTasks;
        long doneTasks;
        long openEstimate;
        long wip;
        long overdue;
        long ownerDefects;
        long openDefects;
        long fixedDefects;
        long introducedDefects;
        long reportedDefects;
        double fixSum;
        int fixN;
        double worklog;
    }

    private String memberName(Long id) {
        Member m = memberMapper.selectById(id);
        return m == null ? "" : m.getName();
    }

    private static Double pct(long part, long total) {
        return total == 0 ? null : round1(part * 100.0 / total);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static boolean isProductionEnv(String env) {
        if (env == null) return false;
        String e = env.toLowerCase(Locale.ROOT);
        return env.contains("生产") || env.contains("线上") || e.contains("prod");
    }

    // ===== GET /mywork =====

    public Map<String, Object> myWork() {
        Long pid = projectContext.currentProjectId();
        Long me = AuthContext.require().id();
        // 未完成 = 未到终态（配置最后一档），已发布/已完成同理
        String taskEnd = settings.terminalStatus(pid, "columns");
        String relEnd = settings.terminalStatus(pid, "relColumns");
        String reqEnd = settings.terminalStatus(pid, "reqColumns");

        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, pid).eq(Task::getAssignee, me)
                .ne(Task::getStatus, taskEnd).orderByAsc(Task::getId));
        List<Task> doneTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, pid).eq(Task::getAssignee, me)
                .eq(Task::getStatus, taskEnd).orderByAsc(Task::getId));
        List<Defect> defects = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                .eq(Defect::getProjectId, pid).eq(Defect::getOwner, me).orderByAsc(Defect::getId));
        List<Release> owned = releaseMapper.selectList(new LambdaQueryWrapper<Release>()
                .eq(Release::getProjectId, pid).eq(Release::getOwner, me)
                .ne(Release::getStatus, relEnd).orderByAsc(Release::getId));
        List<Release> released = releaseMapper.selectList(new LambdaQueryWrapper<Release>()
                .eq(Release::getProjectId, pid).eq(Release::getOwner, me)
                .eq(Release::getStatus, relEnd).orderByAsc(Release::getId));
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, pid).eq(Requirement::getOwner, me)
                .ne(Requirement::getStatus, reqEnd).orderByAsc(Requirement::getId));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", taskService.views(tasks));
        out.put("doneTasks", taskService.views(doneTasks));
        out.put("defects", defectService.views(defects));
        out.put("releasesOwned", releaseService.views(owned));
        out.put("releasesReleased", releaseService.views(released));
        out.put("requirements", requirementService.views(reqs));
        return out;
    }

    // ===== GET /mywork/report（站报内容，仅生成不发送）=====

    public Map<String, Object> report() {
        Long pid = projectContext.currentProjectId();
        Long me = AuthContext.require().id();
        String taskEnd = settings.terminalStatus(pid, "columns");
        String relEnd = settings.terminalStatus(pid, "relColumns");
        String reqEnd = settings.terminalStatus(pid, "reqColumns");

        List<Task> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, pid).eq(Task::getAssignee, me)
                .ne(Task::getStatus, taskEnd).orderByAsc(Task::getId));
        List<Defect> defects = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                .eq(Defect::getProjectId, pid).eq(Defect::getOwner, me)
                .ne(Defect::getStatus, defectService.terminalStatus(pid)).orderByAsc(Defect::getId));
        List<Release> rels = releaseMapper.selectList(new LambdaQueryWrapper<Release>()
                .eq(Release::getProjectId, pid).eq(Release::getOwner, me)
                .ne(Release::getStatus, relEnd).orderByAsc(Release::getId));
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, pid).eq(Requirement::getOwner, me)
                .ne(Requirement::getStatus, reqEnd).orderByAsc(Requirement::getId));

        StringBuilder body = new StringBuilder();
        appendSection(body, "【未完成任务】", tasks.stream().map(t ->
                "- " + t.getId() + " " + t.getTitle() + "（"
                        + settings.statusName(pid, "columns", t.getStatus())
                        + (isBlank(t.getDue()) || "—".equals(t.getDue()) ? "" : " · 到期 " + t.getDue()) + "）").toList());
        appendSection(body, "【待处理缺陷】", defects.stream().map(d ->
                "- " + d.getId() + " " + d.getTitle() + "（"
                        + (d.getPrio() == null ? "" : d.getPrio().toUpperCase()) + " · "
                        + defectService.statusName(pid, d.getStatus()) + "）").toList());
        appendSection(body, "【负责发版】", rels.stream().map(r ->
                "- " + r.getId() + " " + r.getVersion() + "「" + r.getName() + "」（"
                        + settings.statusName(pid, "relColumns", r.getStatus())
                        + (isBlank(r.getDate()) ? "" : " · " + r.getDate()) + "）").toList());
        appendSection(body, "【负责需求】", reqs.stream().map(r ->
                "- " + r.getId() + " " + r.getTitle() + "（"
                        + settings.statusName(pid, "reqColumns", r.getStatus()) + "）").toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", "【每日站报】" + AuthContext.require().name() + " 的待办摘要 · " + Times.today());
        out.put("body", body.toString().stripTrailing());
        return out;
    }

    // ===== GET /activity/feed =====

    public List<Map<String, Object>> feed(Integer limit) {
        int lim = limit == null ? 20 : Math.max(1, Math.min(200, limit));
        Long pid = projectContext.currentProjectId();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (String kind : List.of("task", "defect", "requirement", "release")) {
            activityMapper.selectList(new LambdaQueryWrapper<EntityActivity>()
                            .eq(EntityActivity::getProjectId, pid)
                            .eq(EntityActivity::getKind, kind)
                            .orderByDesc(EntityActivity::getCreatedAt)
                            .orderByDesc(EntityActivity::getId)
                            .last("LIMIT " + lim))
                    .forEach(a -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", a.getRefId() + "-a" + a.getId());
                        row.put("kind", kind);
                        row.put("refId", a.getRefId());
                        row.put("who", a.getMemberId());
                        row.put("type", a.getType());
                        row.put("text", a.getText());
                        row.put("time", Times.format(a.getCreatedAt()));
                        rows.add(row);
                    });
        }
        overviewActivityMapper.selectList(new LambdaQueryWrapper<OverviewActivity>()
                        .orderByDesc(OverviewActivity::getCreatedAt)
                        .orderByDesc(OverviewActivity::getId)
                        .last("LIMIT " + lim))
                .forEach(a -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", "ov" + a.getId());
                    row.put("kind", "overview");
                    row.put("refId", null);
                    row.put("who", null);
                    row.put("type", "log");
                    row.put("text", a.getHtml());
                    row.put("time", Times.format(a.getCreatedAt()));
                    rows.add(row);
                });

        rows.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return rows.size() > lim ? new ArrayList<>(rows.subList(0, lim)) : rows;
    }

    // ===== 内部工具 =====

    /** 流转序列第 n 档 key（越界回退最后一档），n 从 0 开始 */
    private static String nth(List<String> flow, int idx) {
        if (flow == null || flow.isEmpty()) {
            return "";
        }
        return flow.get(Math.min(idx, flow.size() - 1));
    }

    private Iteration activeIter(Long pid) {
        // 进行中迭代 = 迭代工作流的第二档（默认 active）
        String activeKey = nth(settings.flow(pid, "iterColumns"), 1);
        return iterationMapper.selectOne(new LambdaQueryWrapper<Iteration>()
                .eq(Iteration::getProjectId, pid).eq(Iteration::getState, activeKey)
                .orderByDesc(Iteration::getCode).last("LIMIT 1"));
    }

    /** 每类实体「状态变到某值」的最早时间：结构化 status_event 优先，历史 activity 文本兜底 */
    private Map<String, String> statusTime(Long pid, String kind, String toStatus) {
        Map<String, String> out = new HashMap<>();
        for (StatusEvent e : statusEvents.list(pid, kind)) {
            if (toStatus.equals(e.getToStatus())) {
                out.putIfAbsent(e.getEntityId(), Times.format(e.getAt()));
            }
        }
        String suffix = "→ " + toStatus;
        activityMapper.selectList(new LambdaQueryWrapper<EntityActivity>()
                        .eq(EntityActivity::getProjectId, pid)
                        .eq(EntityActivity::getKind, kind)
                        .eq(EntityActivity::getType, "status")
                        .orderByAsc(EntityActivity::getCreatedAt)
                        .orderByAsc(EntityActivity::getId))
                .forEach(a -> {
                    if (a.getText() != null && a.getText().endsWith(suffix)) {
                        out.putIfAbsent(a.getRefId(), Times.format(a.getCreatedAt()));
                    }
                });
        return out;
    }

    private static Map<String, Object> cycleRow(String k, Double avg, String unit,
                                                String note, String emptyNote) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("k", k);
        row.put("v", avg == null ? "—" : fmt(avg) + unit);
        row.put("trend", Map.of("text", "—", "good", true));
        row.put("note", avg == null ? emptyNote : note);
        return row;
    }

    private static Map<String, Object> avgLead(List<Double> hours) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "变更前置时间");
        Double avg = avg(hours);
        row.put("value", avg == null ? "—" : fmt(avg));
        row.put("unit", avg == null ? "" : "小时");
        return row;
    }

    private static Double avg(List<Double> list) {
        return list.isEmpty() ? null : list.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static void appendSection(StringBuilder body, String title, List<String> lines) {
        if (body.length() > 0) body.append("\n\n");
        body.append(title).append('\n');
        if (lines.isEmpty()) {
            body.append("（无）");
            return;
        }
        int shown = Math.min(10, lines.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) body.append('\n');
            body.append(lines.get(i));
        }
        if (lines.size() > shown) {
            body.append('\n').append("… 其余 ").append(lines.size() - shown)
                    .append(" 项请打开工作台查看");
        }
    }

    /** '2026-10-09 14:20:00' / '2026-10-09' → LocalDateTime，无法解析返回 null */
    private static LocalDateTime parseDt(String s) {
        if (isBlank(s)) return null;
        try {
            return LocalDateTime.parse(s.trim(), Times.FULL);
        } catch (RuntimeException e) {
            LocalDate d = Days.parse(s);
            return d == null ? null : d.atStartOfDay();
        }
    }

    /** created/due 等自由日期字段是否落在 [from, to]（含） */
    private static boolean inDays(String s, LocalDate from, LocalDate to) {
        LocalDate d = Days.parse(s);
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    private static String releaseLabel(String range) {
        List<LocalDate> days = Days.range(range == null ? "" : range);
        if (days.isEmpty()) return "—";
        LocalDate end = days.get(days.size() - 1);
        return end.getMonthValue() + " 月 " + end.getDayOfMonth() + " 日发布";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
