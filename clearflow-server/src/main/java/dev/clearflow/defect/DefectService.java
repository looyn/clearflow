package dev.clearflow.defect;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.common.Times;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.infra.StatusEventService;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.module.ProjectModuleService;
import dev.clearflow.notify.NotificationService;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
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

/** 缺陷 CRUD + 单向状态机 + 验证日志 + 关联关系 */
@Service
public class DefectService {

    private final DefectMapper mapper;
    private final VerifyLogMapper verifyLogMapper;
    private final EntityRelationMapper relationMapper;
    private final SequenceService sequence;
    private final CollabService collab;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;
    private final NotificationService notificationService;
    private final MemberMapper memberMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final SettingsService settingsService;
    private final ProjectModuleService moduleService;
    private final StatusEventService statusEvents;
    private final dev.clearflow.collab.ActivityMapper activityMapper;

    public DefectService(DefectMapper mapper, VerifyLogMapper verifyLogMapper,
                         EntityRelationMapper relationMapper, SequenceService sequence,
                         CollabService collab, ProjectContext projectContext,
                         ActivityLog activityLog, NotificationService notificationService,
                         MemberMapper memberMapper, ProjectMemberMapper projectMemberMapper,
                         SettingsService settingsService, ProjectModuleService moduleService,
                         StatusEventService statusEvents,
                         dev.clearflow.collab.ActivityMapper activityMapper) {
        this.mapper = mapper;
        this.verifyLogMapper = verifyLogMapper;
        this.relationMapper = relationMapper;
        this.sequence = sequence;
        this.collab = collab;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
        this.notificationService = notificationService;
        this.memberMapper = memberMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.settingsService = settingsService;
        this.moduleService = moduleService;
        this.statusEvents = statusEvents;
        this.activityMapper = activityMapper;
    }

    public Defect get(String id) {
        Defect d = mapper.selectById(id);
        if (d == null || !d.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("缺陷不存在或不属于当前项目");
        }
        return d;
    }

    /** 缺陷列表（服务端筛选 + 排序 + 真分页） */
    public PageResult<Defect.View> page(int page, int pageSize, BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        Page<Defect> result = mapper.selectPage(new Page<>(page, Math.min(Math.max(1, pageSize), 200)),
                applySort(filtered(projectId, bq), sort, projectId));
        return PageResult.of(views(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 全量导出（按当前筛选，不受分页窗口影响） */
    public List<Defect> listAll(BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        return mapper.selectList(applySort(filtered(projectId, bq), sort, projectId));
    }

    // ===== 看板窗口取数 =====

    /** 一次 GROUP BY 拿到各状态列总数：列头计数与关闭率不受列窗口截断 */
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

    /** 列内窗口：sort 升序，offset/limit 由前端滚动推进 */
    public List<Defect> columnPage(String status, int offset, BoardQuery bq) {
        return mapper.selectList(filtered(projectContext.currentProjectId(), bq)
                .eq("status", status).orderByAsc("sort").orderByAsc("id")
                .last("LIMIT " + bq.limit() + " OFFSET " + Math.max(0, offset)));
    }

    /** 缺陷看板各列首页窗口：列头计数走一次 GROUP BY，卡片装配一次批量 IN */
    public Map<String, Object> boardColumns(BoardQuery bq) {
        List<String> flow = settingsService.defectFlow(projectContext.currentProjectId());
        Map<String, Long> counts = statusCounts(bq);
        Map<String, List<Defect>> rows = new LinkedHashMap<>();
        List<Defect> all = new ArrayList<>();
        for (String key : flow) {
            if (counts.getOrDefault(key, 0L) <= 0) {
                rows.put(key, List.of());
                continue;
            }
            List<Defect> part = columnPage(key, 0, bq);
            rows.put(key, part);
            all.addAll(part);
        }
        Map<String, Defect.View> byId = new HashMap<>();
        views(all).forEach(v -> byId.put(v.id(), v));
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String key : flow) {
            List<Defect> part = rows.getOrDefault(key, List.of());
            long total = counts.getOrDefault(key, 0L);
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("key", key);
            col.put("total", total);
            col.put("items", part.stream().map(t -> byId.get(t.getId())).toList());
            col.put("hasMore", part.size() < total);
            col.put("next", part.size());
            columns.add(col);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("limit", bq.limit());
        return out;
    }

    /** 缺陷列内续取（滚动到底加载下一窗） */
    public Map<String, Object> boardColumnPage(String status, int offset, BoardQuery bq) {
        List<Defect> rows = columnPage(status, offset, bq);
        Map<String, Defect.View> byId = new HashMap<>();
        views(rows).forEach(v -> byId.put(v.id(), v));
        long total = statusCounts(bq).getOrDefault(status, 0L);
        int loaded = Math.max(0, offset) + rows.size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", status);
        out.put("total", total);
        out.put("items", rows.stream().map(t -> byId.get(t.getId())).toList());
        out.put("hasMore", loaded < total);
        out.put("next", loaded);
        return out;
    }

    private QueryWrapper<Defect> filtered(Long projectId, BoardQuery bq) {
        QueryWrapper<Defect> w = new QueryWrapper<Defect>().eq("project_id", projectId);
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
            w.and(x -> x.like("id", like).or().like("title", like).or().like("env", like));
        }
        return w;
    }

    private QueryWrapper<Defect> applySort(QueryWrapper<Defect> w, String sort, Long projectId) {
        switch (sort == null ? "" : sort) {
            case "deadline" -> w.last("ORDER BY CASE WHEN deadline REGEXP '^[0-9]{4}-[0-9]{2}' THEN 0 ELSE 1 END,"
                    + " deadline, id DESC");
            case "prio" -> w.last("ORDER BY " + prioFieldOrder(projectId) + ", id DESC");
            case "sort" -> w.orderByAsc("sort").orderByDesc("id");
            default -> w.orderByDesc("id");
        }
        return w;
    }

    /** 优先级按项目设置里的配置顺序排（key 已过白名单，不把配置文案拼进 SQL） */
    private String prioFieldOrder(Long projectId) {
        String keys = settingsService.priorityNames(projectId).keySet().stream()
                .filter(k -> k.matches("[A-Za-z0-9_-]{1,16}"))
                .map(k -> "'" + k + "'").reduce((a, b) -> a + ", " + b).orElse("''");
        return "FIELD(prio, " + keys + ") = 0, FIELD(prio, " + keys + ")";
    }

    public List<Defect.View> views(List<Defect> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long projectId = rows.get(0).getProjectId();
        List<String> ids = rows.stream().map(Defect::getId).toList();

        Map<String, List<Defect.VerifyView>> verifies = new HashMap<>();
        verifyLogMapper.selectList(new LambdaQueryWrapper<VerifyLog>()
                        .in(VerifyLog::getDefectId, ids)
                        .orderByAsc(VerifyLog::getId))
                .forEach(v -> verifies.computeIfAbsent(v.getDefectId(), x -> new ArrayList<>())
                        .add(new Defect.VerifyView("v" + v.getId(), v.getById(), v.getTime(),
                                v.getResult(), v.getNote() == null ? "" : v.getNote())));

        Map<String, List<Defect.RelateView>> relates = new HashMap<>();
        relationMapper.selectList(new LambdaQueryWrapper<EntityRelation>()
                        .in(EntityRelation::getDefectId, ids)
                        .orderByAsc(EntityRelation::getId))
                .forEach(rl -> relates.computeIfAbsent(rl.getDefectId(), x -> new ArrayList<>())
                        .add(new Defect.RelateView("rl" + rl.getId(), rl.getType(), rl.getTarget())));

        CollabService.Bundle bundle = collab.expand("defect", ids, projectId);
        Map<Long, String> moduleNames = moduleService.nameMap(projectId);
        return rows.stream()
                .map(d -> Defect.View.of(d,
                        d.getModuleId() == null ? "—" : moduleNames.getOrDefault(d.getModuleId(), "—"),
                        verifies.getOrDefault(d.getId(), List.of()),
                        relates.getOrDefault(d.getId(), List.of()),
                        bundle.watchers().getOrDefault(d.getId(), List.of()),
                        bundle.attachments().getOrDefault(d.getId(), List.of()),
                        bundle.activity().getOrDefault(d.getId(), List.of())))
                .toList();
    }

    public Defect.View view(String id) {
        return views(List.of(get(id))).get(0);
    }

    /** 缺陷状态统计（与 mock defectStats 同形；counts 按项目缺陷工作流配置生成） */
    public Map<String, Object> stats() {
        Long projectId = projectContext.currentProjectId();
        Map<String, Long> byStatus = statusCounts(NO_FILTER);

        List<String> flow = settingsService.defectFlow(projectId);
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        flow.forEach(k -> counts.put(k, byStatus.getOrDefault(k, 0L)));

        // 首个状态（待处理/新建态）的优先级分布：按 GROUP BY 统计，不把整表拉回内存
        Map<String, Long> incomingPrio = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<Defect>()
                .select("prio", "COUNT(*) AS cnt").eq("project_id", projectId)
                .eq("status", flow.isEmpty() ? "" : flow.get(0)).groupBy("prio"))) {
            Object k = row.get("prio");
            Object v = row.get("cnt");
            if (k != null && v != null) {
                incomingPrio.put(String.valueOf(k), ((Number) v).longValue());
            }
        }
        String openPrio = settingsService.priorityNames(projectId).entrySet().stream()
                .map(e -> e.getValue() + " " + incomingPrio.getOrDefault(e.getKey(), 0L))
                .collect(java.util.stream.Collectors.joining(" · "));

        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        // 兼容旧字段（前端统计条按 counts 取值，旧 key 保留避免外部消费方缺字段）
        out.put("open", counts.getOrDefault("open", 0L));
        out.put("openPrio", openPrio);
        out.put("fixing", counts.getOrDefault("fixing", 0L));
        out.put("fixed", counts.getOrDefault("fixed", 0L));
        out.put("verified", counts.getOrDefault("verified", 0L));
        out.put("closed", counts.getOrDefault("closed", 0L));
        out.put("total", total);
        return out;
    }

    /** 缺陷状态展示名（取项目缺陷工作流配置，供统计/动态文案复用） */
    public String statusName(Long projectId, String key) {
        return settingsService.defectStatusName(projectId, key);
    }

    /** 缺陷终态 key（流转序列最后一个） */
    public String terminalStatus(Long projectId) {
        return settingsService.defectTerminal(projectId);
    }

    /**
     * 缺陷趋势（近 14 天，全部真实数据）：
     * 新增 = 发现日期（date）按天统计；关闭 = 到达终态的时间按天统计；
     * 存量 = 截至当天的累计新增 - 累计关闭。
     * 关闭时间取状态变更动态（activity 表审计记录）；历史数据无动态时以发现日期兜底。
     */
    public Map<String, Object> trend() {
        Long projectId = projectContext.currentProjectId();
        String terminal = settingsService.defectTerminal(projectId);
        String terminalName = settingsService.defectStatusName(projectId, terminal);

        LocalDate today = LocalDate.now();
        List<String> days = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            days.add(today.minusDays(i).toString());
        }

        // 新增：发现日期前 10 位即日期（2026-10-16 00:00:00），按天 GROUP BY 统计
        Map<String, Long> openedByDay = new HashMap<>();
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<Defect>()
                .select("LEFT(date, 10) AS d", "COUNT(*) AS cnt").eq("project_id", projectId)
                .isNotNull("date").groupBy("d"))) {
            Object d = row.get("d");
            Object c = row.get("cnt");
            if (d != null && c != null) {
                openedByDay.put(String.valueOf(d), ((Number) c).longValue());
            }
        }

        // 关闭：只取终态行的必要列，关闭时间取结构化时间戳 > 状态事件 > 状态变更动态 > 发现日期（历史兜底）
        List<Defect> terminalRows = mapper.selectList(new LambdaQueryWrapper<Defect>()
                .select(Defect::getId, Defect::getDate, Defect::getClosedAt, Defect::getStatus)
                .eq(Defect::getProjectId, projectId).eq(Defect::getStatus, terminal));
        Map<String, LocalDateTime> closedAt = statusEvents.firstEnter(projectId, "defect", terminal);
        List<String> needActivity = terminalRows.stream()
                .filter(d -> d.getClosedAt() == null && closedAt.get(d.getId()) == null)
                .map(Defect::getId).toList();
        Map<String, LocalDateTime> firstStatusChange = new HashMap<>();
        if (!needActivity.isEmpty()) {
            activityMapper.selectList(new LambdaQueryWrapper<dev.clearflow.collab.EntityActivity>()
                            .eq(dev.clearflow.collab.EntityActivity::getProjectId, projectId)
                            .eq(dev.clearflow.collab.EntityActivity::getKind, "defect")
                            .in(dev.clearflow.collab.EntityActivity::getRefId, needActivity)
                            .eq(dev.clearflow.collab.EntityActivity::getType, "status")
                            .like(dev.clearflow.collab.EntityActivity::getText, "→ " + terminalName)
                            .orderByAsc(dev.clearflow.collab.EntityActivity::getCreatedAt))
                    .forEach(a -> {
                        if (a.getCreatedAt() != null) {
                            firstStatusChange.putIfAbsent(a.getRefId(), a.getCreatedAt());
                        }
                    });
        }
        Map<String, Long> closedByDay = new HashMap<>();
        for (Defect d : terminalRows) {
            String day = null;
            if (d.getClosedAt() != null) {
                day = d.getClosedAt().toLocalDate().toString();
            } else if (closedAt.get(d.getId()) != null) {
                day = closedAt.get(d.getId()).toLocalDate().toString();
            } else if (firstStatusChange.get(d.getId()) != null) {
                day = firstStatusChange.get(d.getId()).toLocalDate().toString();
            } else if (d.getDate() != null && d.getDate().length() >= 10) {
                day = d.getDate().substring(0, 10); // 历史数据无动态：以发现日期近似
            }
            if (day != null) {
                closedByDay.merge(day, 1L, Long::sum);
            }
        }

        List<Long> opened = new ArrayList<>();
        List<Long> closed = new ArrayList<>();
        List<Long> stock = new ArrayList<>();
        long cumOpen = 0, cumClose = 0;
        for (String day : days) {
            cumOpen += openedByDay.getOrDefault(day, 0L);
            cumClose += closedByDay.getOrDefault(day, 0L);
            opened.add(openedByDay.getOrDefault(day, 0L));
            closed.add(closedByDay.getOrDefault(day, 0L));
            stock.add(cumOpen - cumClose);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("opened", opened);
        out.put("closed", closed);
        out.put("stock", stock);
        out.put("terminal", terminal);
        return out;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String title = Patch.str(body, "title");
        if (title == null || title.isBlank()) {
            throw BizException.bad("缺陷标题不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        MemberPrincipal actor = AuthContext.require();
        String id = sequence.next("defect");

        Defect d = new Defect();
        d.setId(id);
        d.setProjectId(projectId);
        d.setModuleId(resolveModule(projectId, body));
        d.setTitle(title.trim());
        d.setEnv(body.get("env") == null ? "测试" : Patch.str(body, "env"));
        d.setPrio(Patch.str(body, "prio"));
        d.setSeverity(body.get("severity") == null ? "一般" : Patch.str(body, "severity"));
        // 默认状态取项目缺陷工作流的首个状态（配置里没有 open 时也能正常创建）
        List<String> flow = settingsService.defectFlow(projectId);
        String status = Patch.str(body, "status");
        d.setStatus(status == null || !flow.contains(status) ? flow.get(0) : status);
        d.setSort(nextSort(projectId, d.getStatus()));
        d.setOwner(Patch.lng(body, "owner"));
        applyReporter(d, projectId, body, actor);
        d.setIntroducedBy(projectMember(projectId, Patch.lng(body, "introducedBy"), "引入人"));
        d.setDefectType(body.get("defectType") == null ? "功能" : Patch.str(body, "defectType"));
        d.setRootCause(body.get("rootCause") == null ? "" : Patch.str(body, "rootCause"));
        d.setFixedIn(body.get("fixedIn") == null ? "" : Patch.str(body, "fixedIn"));
        d.setDate(body.get("date") == null ? Times.now() : Patch.str(body, "date"));
        d.setIter(Patch.str(body, "iter"));
        d.setFoundIn(body.get("foundIn") == null ? "—" : Patch.str(body, "foundIn"));
        d.setDeadline(body.get("deadline") == null ? "—" : Patch.str(body, "deadline"));
        d.setReqId(Patch.str(body, "reqId"));
        d.setDescription(Patch.str(body, "desc"));
        d.setReopenCount(0);
        stampLifecycle(d);
        mapper.insert(d);

        collab.push("defect", projectId, id, "create", "提交了缺陷「" + d.getTitle() + "」");
        activityLog.write("<b>" + actor.name() + "</b> 提交缺陷 <span class=\"mr\">#" + id
                + "</span>「" + d.getTitle() + "」");
        notifyHighSeverity(d);
        if (d.getOwner() != null) {
            notificationService.push(d.getOwner(), "assign",
                    "缺陷 " + d.getId() + "「" + d.getTitle() + "」已指派给你", "assign", "defect", d.getId());
        }
        return Map.of("ok", true, "id", id);
    }

    /** P0/P1 缺陷新增 → 通知项目成员（受 notify.defect 开关控制） */
    private void notifyHighSeverity(Defect d) {
        if (!"p0".equals(d.getPrio()) && !"p1".equals(d.getPrio())) {
            return;
        }
        String prioLabel = settingsService.priorityLabel(d.getProjectId(), d.getPrio());
        Member owner = d.getOwner() == null ? null : memberMapper.selectById(d.getOwner());
        String text = prioLabel + " 缺陷 " + d.getId() + "「" + d.getTitle() + "」"
                + (owner == null ? "已提交" : "已指派给" + owner.getName());
        projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, d.getProjectId()))
                .forEach(pm -> notificationService.push(pm.getMemberId(), "defect", text,
                        "defect", "defect", d.getId()));
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Defect d = get(id);

        String nextStatus = Patch.str(patch, "status");
        boolean reopen = patch.containsKey("status") && checkTransition(d, nextStatus);
        collab.changes("defect", d.getProjectId(), id, before(d), patch);
        Long prevOwner = d.getOwner();

        if (patch.containsKey("title")) {
            String title = Patch.str(patch, "title");
            if (title == null || title.isBlank()) {
                throw BizException.bad("缺陷标题不能为空");
            }
            d.setTitle(title.trim());
        }
        if (patch.containsKey("env")) d.setEnv(Patch.str(patch, "env"));
        if (patch.containsKey("prio")) d.setPrio(Patch.str(patch, "prio"));
        if (patch.containsKey("severity")) d.setSeverity(Patch.str(patch, "severity"));
        if (patch.containsKey("moduleId")) d.setModuleId(moduleService.normalize(d.getProjectId(), Patch.lng(patch, "moduleId")));
        if (patch.containsKey("status") && !nextStatus.equals(d.getStatus())) {
            String from = d.getStatus();
            d.setStatus(nextStatus);
            // 换列后排到目标列末尾（列内的精确位置由 PUT /bulk/move 落位）
            d.setSort(nextSort(d.getProjectId(), d.getStatus()));
            if (reopen) {
                // 重开：累计重开次数，清空上一次的修复/关闭时间
                d.setReopenCount((d.getReopenCount() == null ? 0 : d.getReopenCount()) + 1);
                d.setResolvedAt(null);
                d.setClosedAt(null);
            } else {
                stampLifecycle(d);
            }
            statusEvents.record(d.getProjectId(), "defect", d.getId(), from, nextStatus);
        }
        if (patch.containsKey("owner")) d.setOwner(Patch.lng(patch, "owner"));
        if (patch.containsKey("reporterId")) {
            Long rid = Patch.lng(patch, "reporterId");
            if (rid != null) {
                Member m = memberMapper.selectById(rid);
                if (m == null) {
                    throw BizException.bad("报告人不存在");
                }
                d.setReporter(m.getName());
            }
            d.setReporterId(rid);
        }
        if (patch.containsKey("reporter")) {
            // 手填展示名视为外部反馈人（客服/财务等无账号角色），不再关联成员
            d.setReporter(Patch.str(patch, "reporter"));
            if (!patch.containsKey("reporterId")) {
                d.setReporterId(null);
            }
        }
        if (patch.containsKey("introducedBy")) {
            d.setIntroducedBy(projectMember(d.getProjectId(), Patch.lng(patch, "introducedBy"), "引入人"));
        }
        if (patch.containsKey("defectType")) d.setDefectType(Patch.str(patch, "defectType"));
        if (patch.containsKey("rootCause")) d.setRootCause(Patch.str(patch, "rootCause"));
        if (patch.containsKey("fixedIn")) d.setFixedIn(Patch.str(patch, "fixedIn"));
        if (patch.containsKey("date")) d.setDate(Patch.str(patch, "date"));
        if (patch.containsKey("iter")) d.setIter(Patch.str(patch, "iter"));
        if (patch.containsKey("foundIn")) d.setFoundIn(Patch.str(patch, "foundIn"));
        if (patch.containsKey("deadline")) d.setDeadline(Patch.str(patch, "deadline"));
        if (patch.containsKey("reqId")) {
            d.setReqId(Patch.str(patch, "reqId"));
            if (!patch.containsKey("moduleId") && d.getModuleId() == null) {
                d.setModuleId(moduleService.moduleOfRequirement(d.getProjectId(), d.getReqId()));
            }
        }
        if (patch.containsKey("desc")) d.setDescription(Patch.str(patch, "desc"));
        mapper.updateById(d);
        // 改派给他人时通知新负责人（自己改派给自己不通知）
        if (patch.containsKey("owner") && d.getOwner() != null && !d.getOwner().equals(prevOwner)) {
            notificationService.push(d.getOwner(), "assign",
                    "缺陷 " + d.getId() + "「" + d.getTitle() + "」已指派给你", "assign", "defect", d.getId());
        }
        return Map.of("ok", true);
    }

    /**
     * 状态流转校验：
     * 1) 只允许沿工作流前进；
     * 2) 终态 → 任意较早状态 = 重新打开（计入 reopen_count，清空修复/关闭时间）；
     * 3) 非终态不允许随意回退，避免看板状态失去审计意义；
     * 4) 标记已验证前必须至少有一条验证记录。
     *
     * @return true 表示本次操作是「重开」
     */
    private boolean checkTransition(Defect d, String next) {
        List<String> flow = settingsService.defectFlow(d.getProjectId());
        if (next == null || !flow.contains(next)) {
            throw BizException.bad("非法缺陷状态：" + next);
        }
        int from = flow.indexOf(d.getStatus());
        int to = flow.indexOf(next);
        if (to == from) {
            return false;
        }
        if (to < from) {
            if (from != flow.size() - 1) {
                throw BizException.bad("缺陷状态不可回退；如需重新处理，请从终态重新打开");
            }
            return true;
        }
        if ("verified".equals(next) && !"verified".equals(d.getStatus())) {
            long logs = verifyLogMapper.selectCount(new LambdaQueryWrapper<VerifyLog>()
                    .eq(VerifyLog::getDefectId, d.getId()));
            if (logs == 0) {
                throw BizException.bad("请先添加验证记录再标记为已验证");
            }
        }
        return false;
    }

    /** 模块：显式选择优先；未选择时继承关联需求的模块 */
    private Long resolveModule(Long projectId, Map<String, Object> body) {
        Long explicit = moduleService.normalize(projectId, Patch.lng(body, "moduleId"));
        return explicit != null ? explicit
                : moduleService.moduleOfRequirement(projectId, Patch.str(body, "reqId"));
    }

    /** 报告人：内部成员存 member.id + 姓名；外部反馈人（客服/财务）只存展示名 */
    private void applyReporter(Defect d, Long projectId, Map<String, Object> body, MemberPrincipal actor) {
        Long reporterId = Patch.lng(body, "reporterId");
        String reporter = Patch.str(body, "reporter");
        if (reporterId == null && (reporter == null || reporter.isBlank())) {
            reporterId = actor.id();
            reporter = actor.name();
        }
        if (reporterId != null) {
            Member m = memberMapper.selectById(reporterId);
            if (m == null) {
                throw BizException.bad("报告人不存在");
            }
            if (reporter == null || reporter.isBlank()) {
                reporter = m.getName();
            }
        }
        d.setReporterId(reporterId);
        d.setReporter(reporter);
    }

    /** 引入人/报告人等成员字段必须是本项目成员 */
    private Long projectMember(Long projectId, Long memberId, String label) {
        if (memberId == null) {
            return null;
        }
        long count = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getMemberId, memberId));
        if (count == 0) {
            throw BizException.bad(label + "必须是本项目成员");
        }
        return memberId;
    }

    /** 按当前状态打点：已修复档记 resolvedAt，终态记 closedAt（已有更早修复时间则保留） */
    private void stampLifecycle(Defect d) {
        List<String> flow = settingsService.defectFlow(d.getProjectId());
        if (flow.isEmpty()) {
            return;
        }
        String terminal = flow.get(flow.size() - 1);
        String resolved = flow.size() >= 3 ? flow.get(2) : terminal;
        LocalDateTime now = LocalDateTime.now();
        if (resolved.equals(d.getStatus()) && d.getResolvedAt() == null) {
            d.setResolvedAt(now);
        }
        if (terminal.equals(d.getStatus())) {
            if (d.getResolvedAt() == null) {
                d.setResolvedAt(now);
            }
            d.setClosedAt(now);
        }
    }

    private Map<String, Object> before(Defect d) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", d.getStatus());
        m.put("owner", d.getOwner());
        m.put("prio", d.getPrio());
        m.put("severity", d.getSeverity());
        m.put("iter", d.getIter());
        return m;
    }

    /** 看板列内排序步长（整列重排时按序号回写，插入位置由前端给出） */
    private static final long SORT_STEP = 1024;

    /** 无筛选条件（全项目统计用） */
    private static final BoardQuery NO_FILTER = new BoardQuery(null, null, null, null, null, null, 0);

    /** 缺陷全量导出：按当前筛选一次取全，不受看板列窗口与列表分页影响 */
    public Csv export(BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        List<Defect> rows = listAll(bq, sort);
        Map<Long, String> members = memberNames(rows);
        Map<Long, String> moduleNames = moduleService.nameMap(projectId);
        Map<String, String> prioNames = settingsService.priorityNames(projectId);

        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", List.of("编号", "标题", "模块", "缺陷类型", "根因", "引入人", "严重度",
                "优先级", "状态", "处理人", "报告人", "环境", "迭代", "发现版本", "修复版本", "修复期限",
                "重开次数", "创建时间"))).append("\r\n");
        for (Defect d : rows) {
            StringJoiner line = new StringJoiner(",");
            line.add(cell(d.getId()));
            line.add(cell(d.getTitle()));
            line.add(cell(d.getModuleId() == null ? "" : moduleNames.get(d.getModuleId())));
            line.add(cell(d.getDefectType()));
            line.add(cell(d.getRootCause()));
            line.add(cell(d.getIntroducedBy() == null ? "" : members.get(d.getIntroducedBy())));
            line.add(cell(d.getSeverity()));
            line.add(cell(d.getPrio() == null ? "" : prioNames.getOrDefault(d.getPrio(), d.getPrio())));
            line.add(cell(settingsService.defectStatusName(projectId, d.getStatus())));
            line.add(cell(d.getOwner() == null ? "" : members.get(d.getOwner())));
            line.add(cell(d.getReporter()));
            line.add(cell(d.getEnv()));
            line.add(cell(d.getIter()));
            line.add(cell(d.getFoundIn()));
            line.add(cell(d.getFixedIn()));
            line.add(cell(d.getDeadline()));
            line.add(cell(d.getReopenCount() == null ? "0" : String.valueOf(d.getReopenCount())));
            line.add(cell(d.getDate()));
            sb.append(line).append("\r\n");
        }
        return new Csv("defects.csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 导出行内出现的成员姓名（引入人/处理人），一次批量查 */
    private Map<Long, String> memberNames(List<Defect> rows) {
        List<Long> ids = rows.stream()
                .flatMap(d -> java.util.stream.Stream.of(d.getOwner(), d.getIntroducedBy()))
                .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            memberMapper.selectBatchIds(ids).forEach(m -> out.put(m.getId(), m.getName()));
        }
        return out;
    }

    private static String cell(String v) {
        String s = v == null ? "" : v;
        return s.chars().anyMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')
                ? '"' + s.replace("\"", "\"\"") + '"' : s;
    }

    public record Csv(String filename, byte[] data) {
    }

    /** 新建卡片排在该状态列末尾 */
    private long nextSort(Long projectId, String status) {
        Defect last = mapper.selectOne(new LambdaQueryWrapper<Defect>()
                .eq(Defect::getProjectId, projectId).eq(Defect::getStatus, status)
                .orderByDesc(Defect::getSort).last("LIMIT 1"));
        return last == null || last.getSort() == null ? SORT_STEP : last.getSort() + SORT_STEP;
    }

    /** 按前端给的列内顺序回写 sort；只改确实属于该项目该状态的行，其余静默跳过 */
    @Transactional
    private void applyColumnOrder(Long projectId, String status, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            mapper.update(new LambdaUpdateWrapper<Defect>()
                    .eq(Defect::getId, ids.get(i))
                    .eq(Defect::getProjectId, projectId)
                    .eq(Defect::getStatus, status)
                    .set(Defect::getSort, (i + 1) * SORT_STEP));
        }
    }

    /**
     * 看板拖拽单点落位：前端只给锚点卡片与落点方向，上下邻由数据库查，
     * 因此不受列窗口（只加载了前 N 张）截断影响；序号塌缩时整列重编号后再落位。
     */
    @Transactional
    public void moveInColumn(Long projectId, String status, String id, String anchorId, boolean after) {
        if (id.equals(anchorId)) {
            return;
        }
        Long sort = anchorId == null ? null : sortOf(projectId, status, anchorId);
        if (sort == null) {
            setSort(projectId, status, id, nextSort(projectId, status));
            return;
        }
        Defect other = neighborSort(projectId, status, sort, after);
        if (other == null) {
            setSort(projectId, status, id, after ? sort + SORT_STEP : sort - SORT_STEP);
            return;
        }
        long lo = Math.min(sort, other.getSort());
        long hi = Math.max(sort, other.getSort());
        if (hi - lo > 1) {
            setSort(projectId, status, id, (lo + hi) / 2);
            return;
        }
        List<String> ordered = columnIds(projectId, status);
        ordered.remove(id);
        int at = ordered.indexOf(after ? anchorId : other.getId());
        ordered.add(Math.max(0, Math.min(ordered.size(), at + (after ? 1 : 0))), id);
        applyColumnOrder(projectId, status, ordered);
    }

    /** 整列按当前顺序的 id（含前端未加载的行） */
    private List<String> columnIds(Long projectId, String status) {
        return new ArrayList<>(mapper.selectList(new QueryWrapper<Defect>()
                        .select("id").eq("project_id", projectId).eq("status", status)
                        .orderByAsc("sort").orderByAsc("id"))
                .stream().map(Defect::getId).toList());
    }

    private Long sortOf(Long projectId, String status, String id) {
        Defect d = mapper.selectOne(new QueryWrapper<Defect>().select("sort")
                .eq("project_id", projectId).eq("status", status).eq("id", id));
        return d == null ? null : d.getSort();
    }

    /** 锚点同侧最近的一张（after=true 取下一张，false 取上一张） */
    private Defect neighborSort(Long projectId, String status, long anchorSort, boolean after) {
        LambdaQueryWrapper<Defect> w = new LambdaQueryWrapper<Defect>()
                .select(Defect::getId, Defect::getSort)
                .eq(Defect::getProjectId, projectId).eq(Defect::getStatus, status);
        if (after) {
            w.gt(Defect::getSort, anchorSort).orderByAsc(Defect::getSort).orderByAsc(Defect::getId);
        } else {
            w.lt(Defect::getSort, anchorSort).orderByDesc(Defect::getSort).orderByDesc(Defect::getId);
        }
        return mapper.selectOne(w.last("LIMIT 1"));
    }

    private void setSort(Long projectId, String status, String id, long sort) {
        mapper.update(new LambdaUpdateWrapper<Defect>()
                .eq(Defect::getId, id).eq(Defect::getProjectId, projectId)
                .eq(Defect::getStatus, status).set(Defect::getSort, sort));
    }

    @Transactional
    public Map<String, Object> remove(String id) {
        Defect d = get(id);
        mapper.deleteById(id);
        verifyLogMapper.delete(new LambdaQueryWrapper<VerifyLog>().eq(VerifyLog::getDefectId, id));
        relationMapper.delete(new LambdaQueryWrapper<EntityRelation>().eq(EntityRelation::getDefectId, id));
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除缺陷 <span class=\"mr\">#"
                + id + "</span>");
        return Map.of("ok", true);
    }

    // ===== 验证日志 =====

    @Transactional
    public Map<String, Object> addVerifyLog(String defectId, String result, String note) {
        if (result == null || result.isBlank()) {
            throw BizException.bad("验证结果不能为空");
        }
        Defect d = get(defectId);
        VerifyLog v = new VerifyLog();
        v.setProjectId(d.getProjectId());
        v.setDefectId(defectId);
        v.setById(AuthContext.require().id());
        v.setTime(Times.now());
        v.setResult(result);
        v.setNote(note);
        verifyLogMapper.insert(v);

        collab.push("defect", d.getProjectId(), defectId, "verify",
                "验证结果 " + result + (note == null || note.isBlank() ? "" : "：" + note));
        return Map.of("ok", true, "id", "v" + v.getId());
    }

    // ===== 关联关系 =====

    @Transactional
    public Map<String, Object> addRelate(String defectId, String type, String target) {
        if (type == null || type.isBlank() || target == null || target.isBlank()) {
            throw BizException.bad("关联类型与目标不能为空");
        }
        Defect d = get(defectId);
        EntityRelation rl = new EntityRelation();
        rl.setProjectId(d.getProjectId());
        rl.setDefectId(defectId);
        rl.setType(type);
        rl.setTarget(target);
        relationMapper.insert(rl);
        return Map.of("ok", true, "id", "rl" + rl.getId());
    }

    @Transactional
    public Map<String, Object> removeRelate(String defectId, String rawId) {
        Defect d = get(defectId);
        Long rid = CollabService.parseId(rawId, "rl");
        int deleted = relationMapper.delete(new LambdaQueryWrapper<EntityRelation>()
                .eq(EntityRelation::getId, rid)
                .eq(EntityRelation::getDefectId, defectId)
                .eq(EntityRelation::getProjectId, d.getProjectId()));
        if (deleted == 0) {
            throw BizException.notFound("关联不存在");
        }
        return Map.of("ok", true);
    }
}
