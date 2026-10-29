package dev.clearflow.requirement;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.CsvFile;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.common.Times;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.infra.StatusEventService;
import dev.clearflow.iteration.IterationService;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.module.ProjectModuleService;
import dev.clearflow.release.Release;
import dev.clearflow.release.ReleaseMapper;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** 需求 CRUD + 状态机（状态流来自项目设置 → 工作流 → 需求工作流）+ 验收标准 */
@Service
public class RequirementService {

    private final RequirementMapper mapper;
    private final AcceptanceItemMapper acceptanceMapper;
    private final SequenceService sequence;
    private final CollabService collab;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;
    private final ReleaseMapper releaseMapper;
    private final IterationService iterationService;
    private final SettingsService settings;
    private final ProjectModuleService moduleService;
    private final StatusEventService statusEvents;
    private final MemberMapper memberMapper;
    private final TaskMapper taskMapper;
    private final DefectMapper defectMapper;

    public RequirementService(RequirementMapper mapper, AcceptanceItemMapper acceptanceMapper,
                              SequenceService sequence, CollabService collab,
                              ProjectContext projectContext, ActivityLog activityLog,
                              ReleaseMapper releaseMapper, IterationService iterationService,
                              SettingsService settings, ProjectModuleService moduleService,
                              StatusEventService statusEvents, MemberMapper memberMapper,
                              TaskMapper taskMapper, DefectMapper defectMapper) {
        this.mapper = mapper;
        this.acceptanceMapper = acceptanceMapper;
        this.sequence = sequence;
        this.collab = collab;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
        this.releaseMapper = releaseMapper;
        this.iterationService = iterationService;
        this.settings = settings;
        this.moduleService = moduleService;
        this.statusEvents = statusEvents;
        this.memberMapper = memberMapper;
        this.taskMapper = taskMapper;
        this.defectMapper = defectMapper;
    }

    public Requirement get(String id) {
        Requirement r = mapper.selectById(id);
        if (r == null || !r.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("需求不存在或不属于当前项目");
        }
        return r;
    }

    /** 列表行只取的列（description 是 MEDIUMTEXT 正文，列表不下发，详情接口单独取） */
    private static final String[] LIST_COLUMNS = {
            "id", "project_id", "module_id", "title", "type", "prio", "status", "owner",
            "source", "iter", "estimate", "target_release", "created", "completed_at"
    };

    /** 无筛选条件（全项目统计用） */
    private static final BoardQuery NO_FILTER = new BoardQuery(null, null, null, null, null, null, 0);

    /** 需求列表（服务端筛选 + 排序 + 真分页） */
    public PageResult<Requirement.View> page(int page, int pageSize, BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        Page<Requirement> result = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 200)),
                applySort(filtered(projectId, bq), sort, projectId).select(LIST_COLUMNS));
        return PageResult.of(listViews(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 按筛选条件一次取全（导出用，不受分页窗口截断） */
    public List<Requirement> listAll(BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        return mapper.selectList(applySort(filtered(projectId, bq), sort, projectId));
    }

    /** 规划弹窗候选：未排入指定迭代、且未到终态的需求（服务端搜索 + 分页） */
    public PageResult<Requirement.View> planPool(String iter, String q, int page, int pageSize) {
        Long projectId = projectContext.currentProjectId();
        QueryWrapper<Requirement> w = new QueryWrapper<Requirement>()
                .eq("project_id", projectId)
                .and(x -> x.isNull("iter").or().ne("iter", iter == null ? "" : iter))
                .ne("status", settings.terminalStatus(projectId, "reqColumns"))
                .select(LIST_COLUMNS);
        String like = new BoardQuery(q, null, null, null, null, null, 0).likePattern();
        if (like != null) {
            w.and(x -> x.like("id", like).or().like("title", like));
        }
        Page<Requirement> result = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 100)),
                w.orderByDesc("id"));
        return PageResult.of(listViews(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 一次 GROUP BY 拿到各状态需求数：统计条与状态下拉计数不受分页影响 */
    public Map<String, Long> statusCounts(BoardQuery bq) {
        Map<String, Long> out = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(filtered(projectContext.currentProjectId(), bq)
                .select("status", "COUNT(*) AS cnt").groupBy("status"))) {
            Object k = row.get("status");
            Object v = row.get("cnt");
            if (k != null && v != null) {
                out.put(String.valueOf(k), ((Number) v).longValue());
            }
        }
        return out;
    }

    /** 需求统计：总数 / 各状态 / 本季度新增 / 指定迭代内状态分布与点数 / 迭代候选（全部 SQL 聚合） */
    public Map<String, Object> stats(String iter) {
        Long projectId = projectContext.currentProjectId();
        Map<String, Long> byStatus = statusCounts(NO_FILTER);
        String target = iter == null || iter.isBlank() ? null : iter.trim();
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long quarterNew = mapper.selectCount(new QueryWrapper<Requirement>()
                .eq("project_id", projectId).ge("created", quarterStart()));
        List<String> iters = mapper.selectObjs(new QueryWrapper<Requirement>()
                        .select("DISTINCT iter").eq("project_id", projectId)
                        .isNotNull("iter").ne("iter", "").orderByAsc("iter"))
                .stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        Map<String, Object> iterAgg = aggregateOfIter(projectId, target);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("byStatus", byStatus);
        out.put("quarterNew", quarterNew);
        out.put("iter", target);
        out.put("iterByStatus", iterAgg == null ? Map.of() : iterAgg.get("byStatus"));
        out.put("iterTotal", iterAgg == null ? 0L : iterAgg.get("count"));
        out.put("iterPoints", iterAgg == null ? 0 : iterAgg.get("points"));
        out.put("iters", iters);
        return out;
    }

    /** 指定迭代内的需求数与点数合计（GROUP BY 一次取回） */
    private Map<String, Object> aggregateOfIter(Long projectId, String iter) {
        if (iter == null) {
            return null;
        }
        long count = 0;
        long points = 0;
        Map<String, Long> byStatus = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<Requirement>()
                .select("status", "COUNT(*) AS cnt", "COALESCE(SUM(estimate), 0) AS pts")
                .eq("project_id", projectId).eq("iter", iter).groupBy("status"))) {
            Object v = row.get("cnt");
            long cnt = v == null ? 0 : ((Number) v).longValue();
            Object p = row.get("pts");
            points += p == null ? 0 : ((Number) p).longValue();
            count += cnt;
            Object k = row.get("status");
            if (k != null) {
                byStatus.put(String.valueOf(k), cnt);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("count", count);
        out.put("points", points);
        out.put("byStatus", byStatus);
        return out;
    }

    /** 本季度首日（created 是 '2026-10-13 14:30:00' 定长字符串，字典序即时间序） */
    private static String quarterStart() {
        LocalDate today = LocalDate.now();
        return today.withMonth(today.getMonthValue() / 3 * 3 + 1).withDayOfMonth(1) + " 00:00:00";
    }

    private QueryWrapper<Requirement> filtered(Long projectId, BoardQuery bq) {
        QueryWrapper<Requirement> w = new QueryWrapper<Requirement>().eq("project_id", projectId);
        if (bq.status() != null) {
            w.eq("status", bq.status());
        }
        if (bq.prio() != null) {
            w.eq("prio", bq.prio());
        }
        if (bq.owner() != null) {
            w.eq("owner", bq.owner());
        }
        if (bq.iter() != null) {
            w.eq("iter", bq.iter());
        }
        if (bq.moduleId() != null) {
            w.eq("module_id", bq.moduleId());
        }
        String like = bq.likePattern();
        if (like != null) {
            w.and(x -> x.like("id", like).or().like("title", like).or().like("source", like));
        }
        return w;
    }

    private QueryWrapper<Requirement> applySort(QueryWrapper<Requirement> w, String sort, Long projectId) {
        switch (sort == null ? "" : sort) {
            case "estimate" -> w.orderByDesc("estimate").orderByDesc("id");
            case "prio" -> w.last("ORDER BY " + prioFieldOrder(projectId) + ", id DESC");
            case "created" -> w.orderByDesc("created").orderByDesc("id");
            case "id" -> w.orderByAsc("id");
            default -> w.orderByDesc("id");
        }
        return w;
    }

    /** 优先级按项目设置里的配置顺序排（key 已过白名单，不把配置文案拼进 SQL） */
    private String prioFieldOrder(Long projectId) {
        String keys = settings.priorityNames(projectId).keySet().stream()
                .filter(k -> k.matches("[A-Za-z0-9_-]{1,16}"))
                .map(k -> "'" + k + "'").reduce((a, b) -> a + ", " + b).orElse("''");
        return "FIELD(prio, " + keys + ") = 0, FIELD(prio, " + keys + ")";
    }

    /** 列表视图：不含验收标准 / 关注 / 附件 / 动态（详情接口才装配这些） */
    private List<Requirement.View> listViews(List<Requirement> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, String> moduleNames = moduleService.nameMap(rows.get(0).getProjectId());
        return rows.stream()
                .map(r -> Requirement.View.of(r,
                        r.getModuleId() == null ? "—" : moduleNames.getOrDefault(r.getModuleId(), "—"),
                        List.of(), List.of(), List.of(), List.of()))
                .toList();
    }

    /** 批量组装视图（详情/迭代规划等复用） */
    public List<Requirement.View> views(List<Requirement> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long projectId = rows.get(0).getProjectId();
        List<String> ids = rows.stream().map(Requirement::getId).toList();

        Map<String, List<Requirement.AcceptanceView>> acc = new HashMap<>();
        acceptanceMapper.selectList(new LambdaQueryWrapper<AcceptanceItem>()
                        .in(AcceptanceItem::getReqId, ids)
                        .orderByAsc(AcceptanceItem::getId))
                .forEach(a -> acc.computeIfAbsent(a.getReqId(), x -> new ArrayList<>())
                        .add(new Requirement.AcceptanceView("ac" + a.getId(), a.getTitle(),
                                Boolean.TRUE.equals(a.getDone()))));

        CollabService.Bundle bundle = collab.expand("requirement", ids, projectId);
        Map<Long, String> moduleNames = moduleService.nameMap(projectId);
        return rows.stream()
                .map(r -> Requirement.View.of(r,
                        r.getModuleId() == null ? "—" : moduleNames.getOrDefault(r.getModuleId(), "—"),
                        acc.getOrDefault(r.getId(), List.of()),
                        bundle.watchers().getOrDefault(r.getId(), List.of()),
                        bundle.attachments().getOrDefault(r.getId(), List.of()),
                        bundle.activity().getOrDefault(r.getId(), List.of())))
                .toList();
    }

    public Requirement.View view(String id) {
        return views(List.of(get(id))).get(0);
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String title = Patch.str(body, "title");
        if (title == null || title.isBlank()) {
            throw BizException.bad("需求标题不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        String id = sequence.next("requirement");

        Requirement r = new Requirement();
        r.setId(id);
        r.setProjectId(projectId);
        r.setModuleId(moduleService.normalize(projectId, Patch.lng(body, "moduleId")));
        r.setTitle(title.trim());
        r.setType(body.get("type") == null ? "功能" : Patch.str(body, "type"));
        r.setPrio(Patch.str(body, "prio"));
        String status = Patch.str(body, "status");
        List<String> flow = settings.flow(projectId, "reqColumns"); // 项目需求工作流的合法状态
        r.setStatus(status == null || !flow.contains(status) ? flow.get(0) : status);
        r.setOwner(Patch.lng(body, "owner"));
        r.setSource(Patch.str(body, "source"));
        r.setIter(Patch.str(body, "iter"));
        r.setEstimate(Patch.i32(body, "estimate"));
        r.setTargetRelease(body.get("release") == null ? "—" : Patch.str(body, "release"));
        r.setCreated(Times.now()); // 2026-10-13 14:30:00，列表展示完整时间戳
        if (settings.terminalStatus(projectId, "reqColumns").equals(r.getStatus())) {
            r.setCompletedAt(LocalDateTime.now());
        }
        r.setDescription(Patch.str(body, "desc"));
        mapper.insert(r);

        saveAcceptance(id, body.get("acceptance"));

        collab.push("requirement", projectId, id, "create", "创建了需求「" + r.getTitle() + "」");
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 新增需求 <span class=\"mr\">"
                + id + "</span>「" + r.getTitle() + "」");
        return Map.of("ok", true, "id", id);
    }

    /** 创建时落地验收标准：接受 [{title, done}]（忽略客户端临时 id，主键自增） */
    private void saveAcceptance(String reqId, Object raw) {
        if (!(raw instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Object title = m.get("title");
                if (title == null || String.valueOf(title).isBlank()) {
                    continue;
                }
                AcceptanceItem a = new AcceptanceItem();
                a.setReqId(reqId);
                a.setTitle(String.valueOf(title));
                a.setDone(Boolean.TRUE.equals(m.get("done")));
                acceptanceMapper.insert(a);
            }
        }
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Requirement r = get(id);
        collab.changes("requirement", r.getProjectId(), id, before(r), patch);
        String oldIter = r.getIter(); // 变更前旧迭代，改动后用于刷快照

        if (patch.containsKey("title")) {
            String title = Patch.str(patch, "title");
            if (title == null || title.isBlank()) {
                throw BizException.bad("需求标题不能为空");
            }
            r.setTitle(title.trim());
        }
        if (patch.containsKey("type")) r.setType(Patch.str(patch, "type"));
        if (patch.containsKey("prio")) r.setPrio(Patch.str(patch, "prio"));
        if (patch.containsKey("moduleId")) r.setModuleId(moduleService.normalize(r.getProjectId(), Patch.lng(patch, "moduleId")));
        if (patch.containsKey("owner")) r.setOwner(Patch.lng(patch, "owner"));
        if (patch.containsKey("source")) r.setSource(Patch.str(patch, "source"));
        if (patch.containsKey("iter")) r.setIter(Patch.str(patch, "iter"));
        if (patch.containsKey("estimate")) r.setEstimate(Patch.i32(patch, "estimate"));
        if (patch.containsKey("release")) r.setTargetRelease(Patch.str(patch, "release"));
        if (patch.containsKey("desc")) r.setDescription(Patch.str(patch, "desc"));
        if (patch.containsKey("status")) {
            setStatus(r, Patch.str(patch, "status"), false);
        }
        mapper.updateById(r);
        // 排期或状态变化影响迭代点数 → 刷新新旧迭代当日快照（燃尽数据）
        if (patch.containsKey("iter") || patch.containsKey("status")) {
            iterationService.snapshot(r.getProjectId(), r.getIter());
            if (oldIter != null && !oldIter.equals(r.getIter())) {
                iterationService.snapshot(r.getProjectId(), oldIter);
            }
        }
        return Map.of("ok", true);
    }

    private Map<String, Object> before(Requirement r) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", r.getStatus());
        m.put("owner", r.getOwner());
        m.put("prio", r.getPrio());
        m.put("iter", r.getIter());
        return m;
    }

    /** 状态流转（含概览动态与已发布回填版本） */
    @Transactional
    public Map<String, Object> setStatus(String id, String status) {
        Requirement r = get(id);
        setStatus(r, status, true);
        mapper.updateById(r);
        iterationService.snapshot(r.getProjectId(), r.getIter()); // 状态变化刷迭代快照
        return Map.of("ok", true);
    }

    private void setStatus(Requirement r, String status, boolean withOverview) {
        if (status == null || !settings.flow(r.getProjectId(), "reqColumns").contains(status)) {
            throw BizException.bad("非法需求状态：" + status);
        }
        String from = r.getStatus();
        if (!status.equals(from)) {
            collab.changes("requirement", r.getProjectId(), r.getId(),
                    Map.of("status", from), Map.of("status", status));
            r.setStatus(status);
            boolean terminal = settings.terminalStatus(r.getProjectId(), "reqColumns").equals(status);
            // 到达终态记完成时间；离开终态（重开）清空，避免交付周期把重开需求算成已完成
            r.setCompletedAt(terminal ? LocalDateTime.now() : null);
            statusEvents.record(r.getProjectId(), "requirement", r.getId(), from, status);
            // 到达终态（配置最后一档，默认「已发布」）且未指定版本时回填发布版本
            if (terminal && ("—".equals(r.getTargetRelease()) || r.getTargetRelease() == null)) {
                r.setTargetRelease(latestReleasedVersion(r.getProjectId(), r.getId()));
            }
            if (withOverview) {
                activityLog.write("<b>" + AuthContext.require().name() + "</b> 变更需求 <span class=\"mr\">"
                        + r.getId() + "</span> 状态 <span class=\"hl\">" + from + " → " + status + "</span>");
            }
        }
    }

    /** 终态且未指定版本时，回填包含该需求的最近已发布发版计划版本（对齐 mock v2.24.0 行为） */
    private String latestReleasedVersion(Long projectId, String reqId) {
        String relEnd = settings.terminalStatus(projectId, "relColumns"); // 发版终态，默认 released
        List<Release> released = releaseMapper.selectList(new LambdaQueryWrapper<Release>()
                .eq(Release::getProjectId, projectId)
                .eq(Release::getStatus, relEnd)
                .orderByDesc(Release::getId));
        for (Release rel : released) {
            if (rel.getReqIds() != null && rel.getReqIds().contains(reqId)) {
                return rel.getVersion();
            }
        }
        return released.isEmpty() ? "—" : released.get(0).getVersion();
    }

    @Transactional
    public Map<String, Object> remove(String id) {
        Requirement r = get(id);
        mapper.deleteById(id);
        acceptanceMapper.delete(new LambdaQueryWrapper<AcceptanceItem>()
                .eq(AcceptanceItem::getReqId, id));
        iterationService.snapshot(r.getProjectId(), r.getIter()); // 删除影响迭代点数
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除需求 <span class=\"mr\">"
                + id + "</span>「" + (r.getTitle() == null ? "" : r.getTitle()) + "」");
        return Map.of("ok", true);
    }

    /** 验收标准勾选切换 */
    /** 追踪链：本需求关联的任务与缺陷（按 req_id 直查，只取展示字段） */
    public Map<String, Object> links(String id) {
        Requirement r = get(id);
        List<Map<String, Object>> tasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, r.getProjectId())
                        .eq(Task::getReqId, r.getId())
                        .select(Task::getId, Task::getTitle, Task::getStatus, Task::getAssignee)
                        .orderByAsc(Task::getId))
                .stream().map(t -> linkRow(t.getId(), t.getTitle(), t.getStatus(), t.getAssignee())).toList();
        List<Map<String, Object>> defects = defectMapper.selectList(new LambdaQueryWrapper<Defect>()
                        .eq(Defect::getProjectId, r.getProjectId())
                        .eq(Defect::getReqId, r.getId())
                        .select(Defect::getId, Defect::getTitle, Defect::getStatus, Defect::getOwner)
                        .orderByAsc(Defect::getId))
                .stream().map(d -> linkRow(d.getId(), d.getTitle(), d.getStatus(), d.getOwner())).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasks", tasks);
        out.put("defects", defects);
        return out;
    }

    private static Map<String, Object> linkRow(String id, String title, String status, Long member) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("status", status);
        m.put("member", member);
        return m;
    }

    @Transactional
    public Map<String, Object> toggleAcceptance(String reqId, String rawAcId) {
        Requirement r = get(reqId);
        Long acId = CollabService.parseId(rawAcId, "ac");
        AcceptanceItem item = acceptanceMapper.selectOne(new LambdaQueryWrapper<AcceptanceItem>()
                .eq(AcceptanceItem::getId, acId)
                .eq(AcceptanceItem::getReqId, r.getId()));
        if (item == null) {
            throw BizException.notFound("验收标准不存在");
        }
        item.setDone(!Boolean.TRUE.equals(item.getDone()));
        acceptanceMapper.updateById(item);
        return Map.of("ok", true, "done", Boolean.TRUE.equals(item.getDone()));
    }

    /** 需求全量导出：按当前筛选一次取全，不受列表分页窗口截断 */
    public CsvFile exportCsv(BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        List<Requirement> rows = listAll(bq, sort);
        Map<Long, String> moduleNames = moduleService.nameMap(projectId);
        Map<String, String> prioNames = settings.priorityNames(projectId);
        List<Long> owners = rows.stream().map(Requirement::getOwner)
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> members = new HashMap<>();
        if (!owners.isEmpty()) {
            memberMapper.selectBatchIds(owners).forEach(m -> members.put(m.getId(), m.getName()));
        }

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", List.of("编号", "标题", "模块", "类型", "优先级", "状态",
                "负责人", "来源", "迭代", "预估", "版本", "创建"))).append("\r\n");
        for (Requirement r : rows) {
            StringJoiner line = new StringJoiner(",");
            line.add(cell(r.getId()));
            line.add(cell(r.getTitle()));
            line.add(cell(r.getModuleId() == null ? "未归类" : moduleNames.get(r.getModuleId())));
            line.add(cell(r.getType()));
            line.add(cell(r.getPrio() == null ? "" : prioNames.getOrDefault(r.getPrio(), r.getPrio())));
            line.add(cell(r.getStatus()));
            line.add(cell(r.getOwner() == null ? "" : members.get(r.getOwner())));
            line.add(cell(r.getSource()));
            line.add(cell(r.getIter()));
            line.add(cell(r.getEstimate() == null ? "" : String.valueOf(r.getEstimate())));
            line.add(cell(r.getTargetRelease()));
            line.add(cell(r.getCreated()));
            sb.append(line).append("\r\n");
        }
        return new CsvFile("requirements.csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String cell(String v) {
        String s = v == null ? "" : v;
        return s.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }
}
