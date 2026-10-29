package dev.clearflow.iteration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.Jsonz;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.settings.SettingsService;
import dev.clearflow.worklog.Worklog;
import dev.clearflow.worklog.WorklogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 迭代 CRUD + 需求纳入规划 + 复盘 + 真实统计/容量/燃尽 */
@Service
public class IterationService {

    private static final String[] LOAD_COLORS = {"var(--primary)", "var(--green)", "var(--amber)", "var(--t3)"};
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final IterationMapper mapper;
    private final MilestoneMapper milestoneMapper;
    private final RequirementMapper requirementMapper;
    private final MemberMapper memberMapper;
    private final WorklogMapper worklogMapper;
    private final IterationSnapshotMapper snapshotMapper;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;
    private final SettingsService settings;

    public IterationService(IterationMapper mapper, MilestoneMapper milestoneMapper,
                            RequirementMapper requirementMapper, MemberMapper memberMapper,
                            WorklogMapper worklogMapper, IterationSnapshotMapper snapshotMapper,
                            ProjectContext projectContext, ActivityLog activityLog, SettingsService settings) {
        this.mapper = mapper;
        this.milestoneMapper = milestoneMapper;
        this.requirementMapper = requirementMapper;
        this.memberMapper = memberMapper;
        this.worklogMapper = worklogMapper;
        this.snapshotMapper = snapshotMapper;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
        this.settings = settings;
    }

    // ===== 状态语义统一取项目配置（顺序即流转顺序） =====

    /** 迭代「进行中」档 key（iterColumns 第二档，默认 active） */
    private String activeState(Long projectId) {
        return nth(settings.flow(projectId, "iterColumns"), 1);
    }

    /** 迭代首档 key（iterColumns 第一档，默认 planned，新建迭代的初始状态） */
    private String plannedState(Long projectId) {
        return nth(settings.flow(projectId, "iterColumns"), 0);
    }

    /** 迭代终态 key（iterColumns 最后一档，默认 released） */
    private String releasedState(Long projectId) {
        return settings.terminalStatus(projectId, "iterColumns");
    }

    /** 需求终态 key（reqColumns 最后一档，默认「已发布」） */
    private String reqTerminal(Long projectId) {
        return settings.terminalStatus(projectId, "reqColumns");
    }

    /** 需求「已排期」档 key（reqColumns 第二档，纳入迭代时推进到此档） */
    private String reqScheduled(Long projectId) {
        return nth(settings.flow(projectId, "reqColumns"), 1);
    }

    private static String nth(List<String> flow, int idx) {
        return flow == null || flow.isEmpty() ? "" : flow.get(Math.min(idx, flow.size() - 1));
    }

    public Iteration get(String code) {
        Iteration it = mapper.selectById(code);
        if (it == null || !it.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("迭代不存在或不属于当前项目");
        }
        return it;
    }

    /** 迭代列表 + 容量规划 + 统计 + 燃尽（全部按当前项目真实计算） */
    public Map<String, Object> list() {
        Long projectId = projectContext.currentProjectId();
        List<Iteration> rows = mapper.selectList(new LambdaQueryWrapper<Iteration>()
                .eq(Iteration::getProjectId, projectId)
                .orderByDesc(Iteration::getCode));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capacity", capacity(projectId, rows));
        out.put("stats", stats(projectId, rows));
        out.put("burn", burn(projectId, rows));
        return out;
    }

    /**
     * 迭代表分页（scope = current 未完成 / released 终态 / all）
     *
     * <p>「当前 / 已发布」的划分取项目工作流配置的终态档，列表行不下发复盘正文（详情接口单独取）。
     */
    public PageResult<Iteration.View> page(String scope, int page, int pageSize) {
        Long projectId = projectContext.currentProjectId();
        String terminal = releasedState(projectId);
        LambdaQueryWrapper<Iteration> w = new LambdaQueryWrapper<Iteration>()
                .eq(Iteration::getProjectId, projectId);
        if ("released".equals(scope)) {
            w.eq(Iteration::getState, terminal);
        } else if ("current".equals(scope) || scope == null || scope.isBlank()) {
            w.ne(Iteration::getState, terminal);
        }
        w.orderByDesc(Iteration::getCode);
        Page<Iteration> result = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 100)), w);
        // 「已发布」表的卡片要展示复盘内容（一页最多 100 行，正文随页取回）；当前迭代与甘特不带正文
        boolean withRetro = "released".equals(scope);
        return PageResult.of(briefViews(result.getRecords(), withRetro), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 甘特时间轴：全部迭代的一次取全（轻量列，不含复盘正文） */
    public List<Iteration.View> timeline() {
        Long projectId = projectContext.currentProjectId();
        return briefViews(mapper.selectList(new LambdaQueryWrapper<Iteration>()
                .eq(Iteration::getProjectId, projectId)
                .orderByDesc(Iteration::getCode)), false);
    }

    /** 迭代详情（含复盘；复盘存的是 JSON 文本时解析回结构化对象） */
    public Iteration.View detail(String code) {
        Iteration it = get(code);
        return briefViews(List.of(it), true).get(0);
    }

    /** 复盘兼容两种形态：结构化 JSON → 对象；纯文本 → 原样字符串 */
    private static Object retroOf(String raw) {
        if (!Jsonz.looksStructured(raw)) {
            return raw;
        }
        try {
            return Jsonz.tree(raw.trim());
        } catch (Exception e) {
            return raw;
        }
    }

    /** 批量装配列表视图：里程碑一次 IN 取回，复盘正文按 withRetro 决定是否下发 */
    private List<Iteration.View> briefViews(List<Iteration> rows, boolean withRetro) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long projectId = rows.get(0).getProjectId();
        Map<String, List<Iteration.MilestoneView>> miles = new HashMap<>();
        milestoneMapper.selectList(new LambdaQueryWrapper<Milestone>()
                        .eq(Milestone::getProjectId, projectId)
                        .orderByAsc(Milestone::getMsDate))
                .forEach(m -> miles.computeIfAbsent(m.getIterationCode(), x -> new ArrayList<>())
                        .add(new Iteration.MilestoneView(m.getMsDate(), m.getName(), m.getState())));
        return rows.stream()
                .map(it -> Iteration.View.of(it, miles.getOrDefault(it.getCode(), List.of()),
                        withRetro ? retroOf(it.getRetro()) : null))
                .toList();
    }

    /** ===== 统计四项：活跃迭代 / 需求总数 / 已交付需求 / 累计工时（全部真实） ===== */
    private List<Map<String, Object>> stats(Long projectId, List<Iteration> rows) {
        long active = rows.stream().filter(it -> activeState(projectId).equals(it.getState())).count();
        List<Requirement> reqs = requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, projectId));
        long delivered = reqs.stream().filter(r -> reqTerminal(projectId).equals(r.getStatus())).count();
        double hours = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                        .eq(Worklog::getProjectId, projectId)).stream()
                .mapToDouble(w -> w.getHours() == null ? 0 : w.getHours()).sum();
        return List.of(
                Map.of("v", String.valueOf(active), "k", "活跃迭代"),
                Map.of("v", String.valueOf(reqs.size()), "k", "需求总数"),
                Map.of("v", String.valueOf(delivered), "k", "已交付需求"),
                Map.of("v", new java.text.DecimalFormat("#,###").format(hours), "k", "累计工时（小时）"));
    }

    /** ===== 容量卡：当前迭代 / 下一迭代真实负载 + 近 4 周工时趋势 ===== */
    private Map<String, Object> capacity(Long projectId, List<Iteration> rows) {
        Iteration active = rows.stream().filter(it -> activeState(projectId).equals(it.getState())).findFirst().orElse(null);
        Iteration planned = rows.stream().filter(it -> plannedState(projectId).equals(it.getState())).findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("s24", iterLoad(projectId, active, "当前迭代"));
        out.put("s25", iterLoad(projectId, planned, "下一迭代"));
        out.put("loadTrend", loadTrend(projectId));
        return out;
    }

    /** 单个迭代的负载分配：按需求负责人聚合点数占比（前 4 名） */
    private Map<String, Object> iterLoad(Long projectId, Iteration it, String role) {
        Map<String, Object> card = new LinkedHashMap<>();
        if (it == null) {
            card.put("title", "暂无迭代");
            card.put("role", role);
            card.put("rows", List.of());
            card.put("remain", "创建迭代后展示负载分配");
            return card;
        }
        card.put("title", it.getCode() + " · " + it.getName());
        card.put("role", role);

        List<Requirement> reqs = requirementsOf(projectId, it.getCode());
        int total = points(reqs, null);
        Map<Long, Integer> byOwner = reqs.stream()
                .filter(r -> r.getOwner() != null)
                .collect(Collectors.groupingBy(Requirement::getOwner,
                        Collectors.summingInt(r -> est(r))));
        List<Long> top = byOwner.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .limit(4).map(Map.Entry::getKey).toList();
        Map<Long, Member> members = top.isEmpty() ? Map.of()
                : memberMapper.selectBatchIds(top).stream()
                        .collect(Collectors.toMap(Member::getId, m -> m));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            Long owner = top.get(i);
            int pts = byOwner.get(owner);
            rows.add(Map.of("k", members.containsKey(owner) ? members.get(owner).getName() : "成员" + owner,
                    "pct", total == 0 ? 0 : Math.round(pts * 100.0 / total),
                    "color", LOAD_COLORS[i % LOAD_COLORS.length]));
        }
        card.put("rows", rows);
        int done = points(reqs, reqTerminal(projectId));
        card.put("remain", total == 0
                ? "该迭代尚未纳入需求"
                : "剩余 " + (total - done) + " 点 · 已完成 " + done + "/" + total + " 点");
        return card;
    }

    /** 近 4 周工时趋势（worklog 按自然周聚合，周一为周起点） */
    private Map<String, Object> loadTrend(Long projectId) {
        LocalDate today = LocalDate.now();
        LocalDate thisMonday = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        Map<String, Double> weekHours = new LinkedHashMap<>();
        for (int i = 3; i >= 0; i--) {
            weekHours.put(thisMonday.minusWeeks(i).format(D), 0.0);
        }
        for (Worklog w : worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, projectId)
                .ge(Worklog::getWorkDate, thisMonday.minusWeeks(3).format(D))
                .lt(Worklog::getWorkDate, today.plusDays(1).format(D))
                .select(Worklog::getWorkDate, Worklog::getHours))) {
            if (w.getWorkDate() == null || w.getWorkDate().length() < 10) {
                continue;
            }
            try {
                LocalDate d = LocalDate.parse(w.getWorkDate().substring(0, 10), D);
                LocalDate monday = d.minusDays(d.getDayOfWeek().getValue() - 1L);
                String key = monday.format(D);
                if (weekHours.containsKey(key)) {
                    weekHours.merge(key, w.getHours() == null ? 0 : w.getHours(), Double::sum);
                }
            } catch (Exception ignore) {
                // 非标准日期串跳过
            }
        }
        List<Map<String, Object>> weeks = weekHours.entrySet().stream()
                .map(e -> Map.<String, Object>of("label", e.getKey().substring(5), "hours", e.getValue()))
                .toList();
        double sum = weekHours.values().stream().mapToDouble(Double::doubleValue).sum();
        String note = sum == 0 ? "近 4 周暂无工时记录"
                : String.format("近 4 周累计 %.0f 人时 · 周均 %.0f 人时", sum, sum / 4);
        return Map.of("weeks", weeks, "note", note);
    }

    /** ===== 燃尽：进行中迭代的理想线 + 快照实际线（今天实时计算） ===== */
    private Map<String, Object> burn(Long projectId, List<Iteration> rows) {
        Iteration it = rows.stream().filter(i -> activeState(projectId).equals(i.getState())).findFirst().orElse(null);
        if (it == null || it.getIterRange() == null || !it.getIterRange().contains(" ~ ")) {
            return null;
        }
        String[] parts = it.getIterRange().split(" ~ ");
        LocalDate start, end;
        try {
            start = LocalDate.parse(parts[0].trim(), D);
            end = LocalDate.parse(parts[1].trim(), D);
        } catch (Exception e) {
            return null;
        }
        List<Requirement> reqs = requirementsOf(projectId, it.getCode());
        int total = points(reqs, null);
        int done = points(reqs, reqTerminal(projectId));
        int remainingToday = Math.max(0, total - done);

        Map<String, Integer> snaps = snapshotMapper.selectList(new LambdaQueryWrapper<IterationSnapshot>()
                        .eq(IterationSnapshot::getProjectId, projectId)
                        .eq(IterationSnapshot::getIterationCode, it.getCode()))
                .stream()
                .collect(Collectors.toMap(IterationSnapshot::getSnapDate, IterationSnapshot::getRemaining,
                        (a, b) -> b));

        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> series = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            int ideal = Math.round(total * (days - 1 - i) / (float) Math.max(1, days - 1));
            Integer actual = snaps.get(d.format(D));
            if (d.equals(today)) {
                actual = remainingToday; // 今天的实际剩余实时计算，不依赖快照
            } else if (d.isAfter(today)) {
                actual = null; // 未来日期无实际值
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("d", d.format(D));
            p.put("ideal", ideal);
            p.put("actual", actual);
            series.add(p);
        }
        Map<String, Object> burn = new LinkedHashMap<>();
        burn.put("code", it.getCode());
        burn.put("name", it.getName());
        burn.put("start", start.format(D));
        burn.put("end", end.format(D));
        burn.put("total", total);
        burn.put("done", done);
        burn.put("remaining", remainingToday);
        burn.put("series", series);
        return burn;
    }

    /** ===== 快照落库（需求状态/排期变更后调用，供燃尽历史曲线积累） ===== */
    @Transactional
    public void snapshot(Long projectId, String iterCode) {
        if (iterCode == null || iterCode.isBlank() || "待排期".equals(iterCode)) {
            return;
        }
        List<Requirement> reqs = requirementsOf(projectId, iterCode);
        int total = points(reqs, null);
        int done = points(reqs, reqTerminal(projectId));
        String today = LocalDate.now().format(D);
        IterationSnapshot snap = snapshotMapper.selectOne(new LambdaQueryWrapper<IterationSnapshot>()
                .eq(IterationSnapshot::getProjectId, projectId)
                .eq(IterationSnapshot::getIterationCode, iterCode)
                .eq(IterationSnapshot::getSnapDate, today));
        if (snap == null) {
            snap = new IterationSnapshot();
            snap.setProjectId(projectId);
            snap.setIterationCode(iterCode);
            snap.setSnapDate(today);
            snap.setRemaining(total - done);
            snap.setDonePoints(done);
            snapshotMapper.insert(snap);
        } else {
            snap.setRemaining(total - done);
            snap.setDonePoints(done);
            snapshotMapper.updateById(snap);
        }
    }

    /** 定时任务入口：给所有项目的进行中迭代补今日快照（状态档按各项目工作流配置取） */
    @Transactional
    public int snapshotActiveIterations() {
        List<Iteration> all = mapper.selectList(new LambdaQueryWrapper<>());
        int n = 0;
        for (Iteration it : all) {
            if (activeState(it.getProjectId()).equals(it.getState())) {
                snapshot(it.getProjectId(), it.getCode());
                n++;
            }
        }
        return n;
    }

    private List<Requirement> requirementsOf(Long projectId, String iterCode) {
        return requirementMapper.selectList(new LambdaQueryWrapper<Requirement>()
                .eq(Requirement::getProjectId, projectId)
                .eq(Requirement::getIter, iterCode));
    }

    private int points(List<Requirement> reqs, String statusFilter) {
        return reqs.stream()
                .filter(r -> statusFilter == null || statusFilter.equals(r.getStatus()))
                .mapToInt(this::est).sum();
    }

    private int est(Requirement r) {
        return r.getEstimate() == null ? 0 : r.getEstimate();
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String name = Patch.str(body, "name");
        if (name == null || name.isBlank()) {
            throw BizException.bad("迭代名称不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        String code = nextCode(projectId);

        Iteration it = new Iteration();
        it.setCode(code);
        it.setProjectId(projectId);
        it.setName(name.trim());
        it.setIterRange(Patch.str(body, "range"));
        it.setDays("7 天");
        it.setState(plannedState(projectId)); // 初始状态 = 迭代工作流首档（默认 planned）
        it.setPct(0);
        it.setDone(0);
        it.setTotal(Patch.i32(body, "total") == null ? 0 : Patch.i32(body, "total"));
        it.setOwner(Patch.lng(body, "owner"));
        it.setGoal(Patch.str(body, "goal"));
        mapper.insert(it);

        long planned = mapper.selectList(new LambdaQueryWrapper<Iteration>()
                        .eq(Iteration::getProjectId, projectId)
                        .eq(Iteration::getState, plannedState(projectId))).size();
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 规划新迭代 <span class=\"mr\">"
                + code + "</span>「" + it.getName() + "」");
        return Map.of("ok", true, "planned", (int) planned);
    }

    /** S26 起顺延（已存在则继续递增） */
    private String nextCode(Long projectId) {
        int n = 26;
        while (mapper.selectById("S" + n) != null) {
            n++;
        }
        return "S" + n;
    }

    /**
     * 同一项目同时只允许一个「进行中」迭代：页头倒计时、容量、燃尽、工时都按单值取当前迭代，
     * 并行进行中会让这些页面静默显示查询顺序里的第一个，因此把约定落在写入侧。
     */
    private void requireSingleActive(Long projectId, String code, String next) {
        List<String> flow = settings.flow(projectId, "iterColumns");
        if (flow == null || flow.size() < 2 || !nth(flow, 1).equals(next)) {
            return; // 单档工作流没有「进行中」语义，不做限制
        }
        Iteration other = mapper.selectList(new LambdaQueryWrapper<Iteration>()
                        .eq(Iteration::getProjectId, projectId)
                        .eq(Iteration::getState, next)
                        .ne(Iteration::getCode, code)).stream()
                .findFirst().orElse(null);
        if (other != null) {
            throw BizException.bad("「" + other.getCode() + " · " + other.getName()
                    + "」正在进行中，同一项目同时只能有一个进行中迭代；请先发布它或改为其它状态");
        }
    }

    @Transactional
    public Map<String, Object> update(String code, Map<String, Object> patch) {
        Iteration it = get(code);
        if (patch.containsKey("name")) it.setName(Patch.str(patch, "name"));
        if (patch.containsKey("range")) it.setIterRange(Patch.str(patch, "range"));
        if (patch.containsKey("days")) it.setDays(Patch.str(patch, "days"));
        if (patch.containsKey("state")) {
            String next = Patch.str(patch, "state");
            // 与任务/缺陷一致：状态取值必须在项目迭代工作流配置内
            if (next == null || !settings.flow(it.getProjectId(), "iterColumns").contains(next)) {
                throw BizException.bad("非法迭代状态：" + next);
            }
            requireSingleActive(it.getProjectId(), code, next);
            it.setState(next);
        }
        if (patch.containsKey("pct")) it.setPct(Patch.i32(patch, "pct"));
        if (patch.containsKey("done")) it.setDone(Patch.i32(patch, "done"));
        if (patch.containsKey("total")) it.setTotal(Patch.i32(patch, "total"));
        if (patch.containsKey("owner")) it.setOwner(Patch.lng(patch, "owner"));
        if (patch.containsKey("goal")) it.setGoal(Patch.str(patch, "goal"));
        mapper.updateById(it);
        // 里程碑为独立表存储：前端传 milestones 数组时全量重写
        if (patch.containsKey("milestones")) {
            milestoneMapper.delete(new LambdaQueryWrapper<Milestone>()
                    .eq(Milestone::getProjectId, it.getProjectId())
                    .eq(Milestone::getIterationCode, code));
            if (patch.get("milestones") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m) || m.get("d") == null || m.get("n") == null) {
                        continue;
                    }
                    Milestone ms = new Milestone();
                    ms.setProjectId(it.getProjectId());
                    ms.setIterationCode(code);
                    ms.setMsDate(String.valueOf(m.get("d")));
                    ms.setName(String.valueOf(m.get("n")));
                    ms.setState(m.get("state") == null ? "" : String.valueOf(m.get("state")));
                    milestoneMapper.insert(ms);
                }
            }
        }
        return Map.of("ok", true);
    }

    /** 保存复盘（纯文本或结构化 JSON 二态） */
    @Transactional
    public Map<String, Object> saveRetro(String code, String content) {
        Iteration it = get(code);
        it.setRetro(content);
        mapper.updateById(it);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 完成 <span class=\"mr\">"
                + code + "</span> 迭代复盘");
        return Map.of("ok", true);
    }

    /** 需求纳入迭代：设置 iter + 状态「已排期」 */
    @Transactional
    public Map<String, Object> planRequirements(List<String> ids, String iter) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.bad("请选择要纳入的需求");
        }
        if (iter == null || iter.isBlank()) {
            throw BizException.bad("目标迭代不能为空");
        }
        get(iter); // 迭代须存在且属于当前项目
        Long projectId = projectContext.currentProjectId();
        java.util.Set<String> touched = new java.util.HashSet<>();
        int n = 0;
        for (String id : ids) {
            Requirement r = requirementMapper.selectById(id);
            if (r == null || !r.getProjectId().equals(projectId)) {
                continue;
            }
            if (r.getIter() != null) {
                touched.add(r.getIter()); // 旧迭代点数变化，也要刷快照
            }
            r.setIter(iter);
            // 纳入迭代 = 推进到需求工作流第二档（排期档，默认「已排期」）
            r.setStatus(reqScheduled(projectId));
            requirementMapper.updateById(r);
            n++;
        }
        touched.add(iter);
        touched.forEach(code -> snapshot(projectId, code));
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 将 " + n
                + " 个需求纳入 <span class=\"mr\">" + iter + "</span> 迭代");
        return Map.of("ok", true);
    }
}
