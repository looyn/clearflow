package dev.clearflow.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.BoardQuery;
import dev.clearflow.common.Jsonz;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.common.Times;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.infra.StatusEventService;
import dev.clearflow.module.ProjectModuleService;
import dev.clearflow.notify.NotificationService;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 任务 CRUD + 子任务 + 活动审计（状态自由流转，仅校验取值在项目工作流配置内） */
@Service
public class TaskService {

    private final TaskMapper mapper;
    private final SubtaskMapper subtaskMapper;
    private final SequenceService sequence;
    private final CollabService collab;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;
    private final NotificationService notificationService;
    private final SettingsService settings;
    private final ProjectModuleService moduleService;
    private final StatusEventService statusEvents;

    public TaskService(TaskMapper mapper, SubtaskMapper subtaskMapper, SequenceService sequence,
                       CollabService collab, ProjectContext projectContext, ActivityLog activityLog,
                       NotificationService notificationService, SettingsService settings,
                       ProjectModuleService moduleService, StatusEventService statusEvents) {
        this.mapper = mapper;
        this.subtaskMapper = subtaskMapper;
        this.sequence = sequence;
        this.collab = collab;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
        this.notificationService = notificationService;
        this.settings = settings;
        this.moduleService = moduleService;
        this.statusEvents = statusEvents;
    }

    public Task get(String id) {
        Task t = mapper.selectById(id);
        if (t == null || !t.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("任务不存在或不属于当前项目");
        }
        return t;
    }

    /** 批量组装视图（看板/我的工作/批量接口复用，避免 N+1） */
    public List<Task.View> views(List<Task> tasks) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Long projectId = tasks.get(0).getProjectId();
        List<String> ids = tasks.stream().map(Task::getId).toList();

        Map<String, List<Subtask.View>> subs = new HashMap<>();
        subtaskMapper.selectList(new LambdaQueryWrapper<Subtask>()
                        .eq(Subtask::getProjectId, projectId)
                        .in(Subtask::getTaskId, ids)
                        .orderByAsc(Subtask::getId))
                .forEach(s -> subs.computeIfAbsent(s.getTaskId(), x -> new ArrayList<>())
                        .add(Subtask.View.of(s)));

        CollabService.Bundle bundle = collab.expand("task", ids, projectId);
        Map<Long, String> moduleNames = moduleService.nameMap(projectId);
        return tasks.stream()
                .map(t -> Task.View.of(t,
                        t.getModuleId() == null ? "—" : moduleNames.getOrDefault(t.getModuleId(), "—"),
                        subs.getOrDefault(t.getId(), List.of()),
                        bundle.watchers().getOrDefault(t.getId(), List.of()),
                        bundle.attachments().getOrDefault(t.getId(), List.of()),
                        bundle.activity().getOrDefault(t.getId(), List.of())))
                .toList();
    }

    public Task.View view(String id) {
        return views(List.of(get(id))).get(0);
    }

    // ===== 看板窗口取数 / 列表分页（筛选与排序全部下推 SQL）=====

    /** 一次 GROUP BY 拿到各状态列的总数：列头计数不受前端只加载了前 N 张影响 */
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
    public List<Task> columnPage(String status, int offset, BoardQuery bq) {
        return mapper.selectList(filtered(projectContext.currentProjectId(), bq)
                .eq("status", status).orderByAsc("sort").orderByAsc("id")
                .last("LIMIT " + bq.limit() + " OFFSET " + Math.max(0, offset)));
    }

    /** 列表视图分页（新在前？与看板同口径：手动排序值优先，其次编号倒序） */
    public PageResult<Task.View> page(int page, int pageSize, BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        QueryWrapper<Task> w = filtered(projectId, bq);
        Page<Task> result = mapper.selectPage(new Page<>(page, Math.min(Math.max(1, pageSize), 200)),
                applySort(w, sort, projectId));
        return PageResult.of(views(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 全量导出用的顺序（与列表同排序，不分页） */
    public List<Task> listAll(BoardQuery bq, String sort) {
        Long projectId = projectContext.currentProjectId();
        return mapper.selectList(applySort(filtered(projectId, bq), sort, projectId));
    }

    private QueryWrapper<Task> filtered(Long projectId, BoardQuery bq) {
        QueryWrapper<Task> w = new QueryWrapper<Task>().eq("project_id", projectId);
        if (bq.status() != null) {
            w.eq("status", bq.status());
        }
        if (bq.prio() != null) {
            w.eq("prio", bq.prio());
        }
        if (bq.owner() != null) {
            w.eq("assignee", bq.owner());
        }
        if (bq.iter() != null) {
            w.eq("iter", bq.iter());
        }
        if (bq.moduleId() != null) {
            w.eq("module_id", bq.moduleId());
        }
        String like = bq.likePattern();
        if (like != null) {
            w.and(x -> x.like("id", like).or().like("title", like)
                    .or().apply("CAST(labels AS CHAR) LIKE {0}", like));
        }
        return w;
    }

    private QueryWrapper<Task> applySort(QueryWrapper<Task> w, String sort, Long projectId) {
        switch (sort == null ? "" : sort) {
            case "due" -> w.last("ORDER BY CASE WHEN due REGEXP '^[0-9]{4}-[0-9]{2}' THEN 0 ELSE 1 END, due, id");
            case "estimate" -> w.orderByDesc("estimate").orderByDesc("id");
            case "prio" -> w.last("ORDER BY " + prioFieldOrder(projectId) + ", id");
            case "id" -> w.orderByDesc("id");
            default -> w.orderByAsc("sort").orderByDesc("id");
        }
        return w;
    }

    /** 优先级按项目设置里的配置顺序排（key 已过白名单，配置里的中文名不进 SQL） */
    private String prioFieldOrder(Long projectId) {
        String keys = settings.priorityNames(projectId).keySet().stream()
                .filter(k -> k.matches("[A-Za-z0-9_-]{1,16}"))
                .map(k -> "'" + k + "'").reduce((a, b) -> a + ", " + b).orElse("''");
        return "FIELD(prio, " + keys + ") = 0, FIELD(prio, " + keys + "), id";
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String title = Patch.str(body, "title");
        if (title == null || title.isBlank()) {
            throw BizException.bad("任务标题不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        MemberPrincipal actor = AuthContext.require();

        Task t = new Task();
        t.setId(sequence.next("task"));
        t.setProjectId(projectId);
        Long moduleId = moduleService.normalize(projectId, Patch.lng(body, "moduleId"));
        t.setModuleId(moduleId != null ? moduleId : moduleService.moduleOfRequirement(projectId, Patch.str(body, "reqId")));
        t.setTitle(title.trim());
        t.setType(Patch.str(body, "type"));
        t.setPrio(Patch.str(body, "prio"));
        t.setAssignee(Patch.lng(body, "assignee"));
        t.setDue(body.get("due") == null ? "待排期" : String.valueOf(body.get("due")));
        t.setHot(Boolean.TRUE.equals(body.get("hot")));
        String status = Patch.str(body, "status");
        List<String> flow = settings.flow(projectId, "columns"); // 项目工作流配置的合法状态
        t.setStatus(status == null || !flow.contains(status) ? flow.get(0) : status);
        t.setSort(nextSort(projectId, t.getStatus()));
        t.setIter(Patch.str(body, "iter"));
        t.setEstimate(Patch.i32(body, "estimate"));
        t.setSpent(body.get("spent") == null ? 0d : Patch.dbl(body, "spent"));
        t.setReqId(Patch.str(body, "reqId"));
        t.setLabels(jsonList(Patch.strs(body, "labels")));
        t.setPredIds(jsonList(Patch.strs(body, "predIds")));
        t.setCreator(actor.id());
        t.setCreated(Times.now());
        t.setUpdated(Times.now());
        if (settings.terminalStatus(projectId, "columns").equals(t.getStatus())) {
            t.setCompletedAt(LocalDateTime.now());
        }
        t.setDescription(Patch.str(body, "desc"));
        mapper.insert(t);

        collab.push("task", projectId, t.getId(), "create", "创建了任务「" + t.getTitle() + "」");
        activityLog.write("<b>" + actor.name() + "</b> 创建任务 <span class=\"mr\">" + t.getId()
                + "</span>「" + t.getTitle() + "」");
        notifyAssignee(t, "任务 " + t.getId() + "「" + t.getTitle() + "」已指派给你");
        return Map.of("ok", true, "id", t.getId());
    }

    /** 指派通知（收件人按自己的 assign 开关决定是否接收；push 内部会跳过指派给自己） */
    private void notifyAssignee(Task t, String text) {
        if (t.getAssignee() != null) {
            notificationService.push(t.getAssignee(), "assign", text, "assign", "task", t.getId());
        }
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Task t = get(id);
        String status = Patch.str(patch, "status");
        if (patch.containsKey("status") && (status == null || !settings.flow(t.getProjectId(), "columns").contains(status))) {
            throw BizException.bad("非法任务状态：" + status);
        }
        // 覆盖前比对关键字段旧→新，生成「将状态 待处理 → 进行中」类动态
        collab.changes("task", t.getProjectId(), t.getId(), before(t), patch);
        Long prevAssignee = t.getAssignee();

        if (patch.containsKey("title")) {
            String title = Patch.str(patch, "title");
            if (title == null || title.isBlank()) {
                throw BizException.bad("任务标题不能为空");
            }
            t.setTitle(title.trim());
        }
        if (patch.containsKey("type")) t.setType(Patch.str(patch, "type"));
        if (patch.containsKey("prio")) t.setPrio(Patch.str(patch, "prio"));
        if (patch.containsKey("moduleId")) t.setModuleId(moduleService.normalize(t.getProjectId(), Patch.lng(patch, "moduleId")));
        if (patch.containsKey("assignee")) t.setAssignee(Patch.lng(patch, "assignee"));
        if (patch.containsKey("due")) t.setDue(Patch.str(patch, "due"));
        if (patch.containsKey("hot")) t.setHot(Patch.bool(patch, "hot"));
        if (patch.containsKey("status") && !status.equals(t.getStatus())) {
            String from = t.getStatus();
            t.setStatus(status);
            // 换列后排到目标列末尾（列内的精确位置由 PUT /bulk/move 落位）
            t.setSort(nextSort(t.getProjectId(), status));
            boolean terminal = settings.terminalStatus(t.getProjectId(), "columns").equals(status);
            t.setCompletedAt(terminal ? LocalDateTime.now() : null);
            statusEvents.record(t.getProjectId(), "task", t.getId(), from, status);
        }
        if (patch.containsKey("iter")) t.setIter(Patch.str(patch, "iter"));
        if (patch.containsKey("estimate")) t.setEstimate(Patch.i32(patch, "estimate"));
        if (patch.containsKey("spent")) t.setSpent(Patch.dbl(patch, "spent"));
        if (patch.containsKey("reqId")) {
            t.setReqId(Patch.str(patch, "reqId"));
            // 未显式选模块时，跟随关联需求的模块（只补空值，不覆盖人工归类）
            if (!patch.containsKey("moduleId") && t.getModuleId() == null) {
                t.setModuleId(moduleService.moduleOfRequirement(t.getProjectId(), t.getReqId()));
            }
        }
        if (patch.containsKey("labels")) t.setLabels(jsonList(Patch.strs(patch, "labels")));
        if (patch.containsKey("predIds")) t.setPredIds(jsonList(Patch.strs(patch, "predIds")));
        if (patch.containsKey("desc")) t.setDescription(Patch.str(patch, "desc"));
        t.setUpdated(Times.now());
        mapper.updateById(t);
        // 指派给他人时通知新负责人（自己指派给自己不通知）
        if (patch.containsKey("assignee") && t.getAssignee() != null && !t.getAssignee().equals(prevAssignee)) {
            notifyAssignee(t, "任务 " + t.getId() + "「" + t.getTitle() + "」已指派给你");
        }
        return Map.of("ok", true);
    }

    private Map<String, Object> before(Task t) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", t.getStatus());
        m.put("assignee", t.getAssignee());
        m.put("prio", t.getPrio());
        m.put("iter", t.getIter());
        return m;
    }

    /** 看板列内排序步长（整列重排时按序号回写，插入位置由前端给出） */
    private static final long SORT_STEP = 1024;

    /** 新建卡片排在该状态列末尾 */
    private long nextSort(Long projectId, String status) {
        Task last = mapper.selectOne(new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, projectId).eq(Task::getStatus, status)
                .orderByDesc(Task::getSort).last("LIMIT 1"));
        return last == null || last.getSort() == null ? SORT_STEP : last.getSort() + SORT_STEP;
    }

    /** 按前端给的列内顺序回写 sort；只改确实属于该项目该状态的行，其余静默跳过 */
    @Transactional
    private void applyColumnOrder(Long projectId, String status, List<String> ids) {
        for (int i = 0; i < ids.size(); i++) {
            mapper.update(new LambdaUpdateWrapper<Task>()
                    .eq(Task::getId, ids.get(i))
                    .eq(Task::getProjectId, projectId)
                    .eq(Task::getStatus, status)
                    .set(Task::getSort, (i + 1) * SORT_STEP));
        }
    }

    /**
     * 看板拖拽单点落位：前端只给锚点卡片和落点方向，真正的上下邻由数据库查，
     * 因此不受列窗口（只加载了前 N 张）截断的影响。相邻序号塌缩时整列重编号后再落位。
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
        Task other = neighborSort(projectId, status, sort, after);
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
        // 两侧序号已无空位：按拖后的完整顺序重编号整列
        List<String> ordered = columnIds(projectId, status);
        ordered.remove(id);
        int at = ordered.indexOf(after ? anchorId : other.getId());
        ordered.add(Math.max(0, Math.min(ordered.size(), at + (after ? 1 : 0))), id);
        applyColumnOrder(projectId, status, ordered);
    }

    /** 整列按当前顺序的 id 列表（含前端未加载的行） */
    private List<String> columnIds(Long projectId, String status) {
        return new ArrayList<>(mapper.selectList(new QueryWrapper<Task>()
                        .select("id").eq("project_id", projectId).eq("status", status)
                        .orderByAsc("sort").orderByAsc("id"))
                .stream().map(Task::getId).toList());
    }

    private Long sortOf(Long projectId, String status, String id) {
        Task t = mapper.selectOne(new QueryWrapper<Task>().select("sort")
                .eq("project_id", projectId).eq("status", status).eq("id", id));
        return t == null ? null : t.getSort();
    }

    /** 锚点同侧最近的一张（after=true 取下一张，false 取上一张） */
    private Task neighborSort(Long projectId, String status, long anchorSort, boolean after) {
        LambdaQueryWrapper<Task> w = new LambdaQueryWrapper<Task>()
                .select(Task::getId, Task::getSort)
                .eq(Task::getProjectId, projectId).eq(Task::getStatus, status);
        if (after) {
            w.gt(Task::getSort, anchorSort).orderByAsc(Task::getSort).orderByAsc(Task::getId);
        } else {
            w.lt(Task::getSort, anchorSort).orderByDesc(Task::getSort).orderByDesc(Task::getId);
        }
        return mapper.selectOne(w.last("LIMIT 1"));
    }

    private void setSort(Long projectId, String status, String id, long sort) {
        mapper.update(new LambdaUpdateWrapper<Task>()
                .eq(Task::getId, id).eq(Task::getProjectId, projectId).eq(Task::getStatus, status)
                .set(Task::getSort, sort));
    }

    @Transactional
    public Map<String, Object> remove(String id) {
        Task t = get(id);
        mapper.deleteById(id);
        subtaskMapper.delete(new LambdaQueryWrapper<Subtask>().eq(Subtask::getTaskId, id));
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除任务 <span class=\"mr\">"
                + id + "</span>「" + (t.getTitle() == null ? "" : t.getTitle()) + "」");
        return Map.of("ok", true);
    }

    // ===== 子任务 =====

    @Transactional
    public Map<String, Object> addSubtask(String taskId, String title) {
        if (title == null || title.isBlank()) {
            throw BizException.bad("子任务标题不能为空");
        }
        Task t = get(taskId);
        Subtask s = new Subtask();
        s.setProjectId(t.getProjectId());
        s.setTaskId(taskId);
        s.setTitle(title.trim());
        s.setDone(false);
        subtaskMapper.insert(s);
        collab.push("task", t.getProjectId(), taskId, "subtask", "添加子任务「" + s.getTitle() + "」");
        return Map.of("ok", true, "id", "s" + s.getId());
    }

    @Transactional
    public Map<String, Object> toggleSubtask(String taskId, String rawSid) {
        Task t = get(taskId);
        Subtask s = subtask(rawSid, taskId);
        s.setDone(!Boolean.TRUE.equals(s.getDone()));
        subtaskMapper.updateById(s);
        collab.push("task", t.getProjectId(), taskId, "subtask",
                (Boolean.TRUE.equals(s.getDone()) ? "完成" : "重开") + "子任务「" + s.getTitle() + "」");
        return Map.of("ok", true, "done", Boolean.TRUE.equals(s.getDone()));
    }

    @Transactional
    public Map<String, Object> removeSubtask(String taskId, String rawSid) {
        Task t = get(taskId);
        Subtask s = subtask(rawSid, taskId);
        subtaskMapper.deleteById(s.getId());
        collab.push("task", t.getProjectId(), taskId, "subtask", "删除子任务「" + s.getTitle() + "」");
        return Map.of("ok", true);
    }

    private Subtask subtask(String rawSid, String taskId) {
        Long sid = CollabService.parseId(rawSid, "s");
        Subtask s = subtaskMapper.selectOne(new LambdaQueryWrapper<Subtask>()
                .eq(Subtask::getId, sid).eq(Subtask::getTaskId, taskId));
        if (s == null) {
            throw BizException.notFound("子任务不存在");
        }
        return s;
    }

    /** 列表 → JSON 存储（null 视为空数组） */
    private static String jsonList(List<String> list) {
        return Jsonz.write(list == null ? List.of() : list);
    }
}
