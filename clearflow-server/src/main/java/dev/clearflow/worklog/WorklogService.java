package dev.clearflow.worklog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.Days;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.iteration.Iteration;
import dev.clearflow.iteration.IterationMapper;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.project.Project;
import dev.clearflow.project.ProjectMapper;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工时：GET 汇总（迭代窗口）+ 记录分页；POST 登记 / PUT 编辑 / DELETE 删除 / POST batch 周填报。
 * 关联对象用 ref_kind + ref_id 显式保存（task / defect / other），旧数据按 ref_text 前缀推断；
 * 任务与缺陷的已投入工时（spent）随登记、编辑、删除同步增减。
 * 统计区间全部在 SQL 侧按 work_date 过滤，接口只取窗口/分页/热力所需字段。
 */
@Service
public class WorklogService {

    private static final Pattern TASK_REF = Pattern.compile("^\\s*(T-\\d+)");
    private static final Pattern DEFECT_REF = Pattern.compile("^\\s*(B-\\d+)");
    private static final String KIND_TASK = "task";
    private static final String KIND_DEFECT = "defect";
    private static final String KIND_OTHER = "other";
    private static final String[] TYPE_KEYS = {"前端开发", "后端开发", "测试", "设计", "缺陷修复", "运维 / 其他"};
    private static final String[] TYPE_COLORS = {"#3370FF", "#22C55E", "#7A5AF8", "#FF7D00", "#F53F3F", "#8F959E"};
    /** member.capacity 语义：每迭代可用工时（小时） */
    private static final int DEFAULT_CAPACITY = 100;
    private static final int HEAT_DAYS = 14;
    /** 单日（跨项目）累计工时上限 */
    private static final int MAX_DAY_HOURS = 24;
    /** 一次批量填报最多天数 */
    private static final int MAX_BATCH_DAYS = 14;

    private final WorklogMapper worklogMapper;
    private final IterationMapper iterationMapper;
    private final TaskMapper taskMapper;
    private final DefectMapper defectMapper;
    private final MemberMapper memberMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final ProjectContext projectContext;
    private final SequenceService sequence;
    private final ActivityLog activityLog;

    public WorklogService(WorklogMapper worklogMapper, IterationMapper iterationMapper,
                          TaskMapper taskMapper, DefectMapper defectMapper,
                          MemberMapper memberMapper, ProjectMemberMapper projectMemberMapper,
                          ProjectMapper projectMapper, ProjectContext projectContext,
                          SequenceService sequence, ActivityLog activityLog) {
        this.worklogMapper = worklogMapper;
        this.iterationMapper = iterationMapper;
        this.taskMapper = taskMapper;
        this.defectMapper = defectMapper;
        this.memberMapper = memberMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.projectContext = projectContext;
        this.sequence = sequence;
        this.activityLog = activityLog;
    }

    // ===== GET /worklog =====

    public Map<String, Object> page(int pageNo, int pageSize) {
        Long pid = projectContext.currentProjectId();
        int size = Math.min(100, Math.max(1, pageSize));
        LocalDate today = LocalDate.now();
        MemberPrincipal me = AuthContext.require();
        Long selfId = me.id();
        // 团队视图：系统管理员或项目负责人；其余成员只看本人数据
        boolean team = me.admin() || isOwner(pid, selfId);

        Iteration active = activeIteration(pid);
        List<LocalDate> window = Days.window(active == null ? null : active.getIterRange());
        LocalDate winStart = window.get(0);
        LocalDate winEnd = window.get(window.size() - 1);

        // 窗口内工时：只取聚合所需列，日期范围下推到 SQL
        List<Worklog> winRows = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, selfId)
                .ge(Worklog::getWorkDate, winStart.toString())
                .lt(Worklog::getWorkDate, winEnd.plusDays(1).toString())
                .select(Worklog::getMemberId, Worklog::getWorkDate, Worklog::getRefText,
                        Worklog::getRefKind, Worklog::getRefId, Worklog::getHours));
        double winSum = sum(winRows);

        // 统计范围：团队视图取项目全体成员；个人视图只取本人
        List<Long> memberIds;
        if (team) {
            memberIds = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                            .eq(ProjectMember::getProjectId, pid).orderByAsc(ProjectMember::getMemberId))
                    .stream().map(ProjectMember::getMemberId).toList();
        } else {
            memberIds = List.of(selfId);
        }
        Map<Long, Member> members = membersOf(memberIds);

        // ===== summary =====
        List<Map<String, Object>> summary = new ArrayList<>();

        // ① 窗口累计工时与容量预算（迭代窗口按成员容量折算，无迭代时按 8h/人/天）
        long elapsed = Math.max(1, window.stream().filter(d -> !d.isAfter(today)).count());
        int capTotal = memberIds.stream().map(members::get).filter(Objects::nonNull)
                .mapToInt(WorklogService::cap).sum();
        boolean byCapacity = active != null && capTotal > 0;
        int budget = byCapacity
                ? Math.max(1, (int) Math.round(capTotal * (double) elapsed / window.size()))
                : (int) Math.max(1, Math.max(1, memberIds.size()) * 8L * elapsed);
        String usedPct = winSum <= 0 ? "0.0" : fmt(winSum * 100 / budget);
        String capLabel = byCapacity
                ? (team ? "容量预算 " : "个人容量预算 ")
                : (team ? "按 8h/人/天预算 " : "按 8h/天预算 ");
        summary.add(Map.of(
                "k", active == null ? "近 7 天累计工时" : active.getCode() + " 累计工时",
                "v", r1(winSum) + " h",
                "sub", capLabel + budget + " h · 已用 " + usedPct + "%"));

        // ② 本周（周一至今）：团队看人均，个人看本人
        double weekSum = sumBetween(pid, Days.monday(), today, team, selfId);
        double perCap = memberIds.isEmpty() ? 1 : memberIds.size();
        summary.add(team
                ? Map.of("k", "本周人均工时", "v", r1(weekSum / perCap) + " h",
                        "sub", memberIds.size() + " 名成员")
                : Map.of("k", "本周工时", "v", r1(weekSum) + " h", "sub", "个人登记"));

        // ③ 记录条数（全量，不受分页影响）
        long allCount = worklogMapper.selectCount(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, selfId));
        long todayNew = countBetween(pid, today, today, team, selfId);
        summary.add(Map.of(
                "k", "记录条数",
                "v", allCount + " 条",
                "sub", "今日新增 " + todayNew + " 条"));

        // ④ 预估偏差（仅有估算的任务参与：实际工时来自 task.spent，登记时同步累加）
        List<Task> estimated = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getProjectId, pid).gt(Task::getEstimate, 0)
                .eq(!team, Task::getAssignee, selfId)
                .select(Task::getEstimate, Task::getSpent));
        double est = estimated.stream().mapToDouble(t -> t.getEstimate() == null ? 0 : t.getEstimate()).sum();
        double act = estimated.stream().mapToDouble(t -> t.getSpent() == null ? 0 : t.getSpent()).sum();
        summary.add(Map.of(
                "k", "预估偏差",
                "v", est <= 0 ? "—" : String.format(Locale.ROOT, "%+.1f%%", (act - est) / est * 100),
                "sub", est <= 0 ? "暂无已估算任务"
                        : estimated.size() + (team ? " 个任务" : " 个我负责的任务")
                        + " · 实际 " + r1(act) + " / 估算 " + r1(est) + " h"));

        // ===== byMember（窗口工时 ÷ capacity，容量为「小时/迭代」）=====
        Map<Long, Double> byMemberHours = new HashMap<>();
        winRows.forEach(w -> byMemberHours.merge(w.getMemberId(), h(w), Double::sum));
        List<Map<String, Object>> byMember = new ArrayList<>();
        for (Long id : memberIds) {
            Member m = members.get(id);
            if (m == null) continue;
            int cap = cap(m);
            double h = r1(byMemberHours.getOrDefault(id, 0.0));
            byMember.add(Map.of(
                    "id", id, "name", m.getName(), "hours", h, "budget", cap,
                    "cls", h > cap ? "over" : h >= cap * 0.8 ? "warm" : "cool"));
        }

        // ===== byType（窗口工时：任务按工种、缺陷单列、其余运维/其他）=====
        Map<String, String> taskType = new HashMap<>();
        taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .eq(Task::getProjectId, pid).select(Task::getId, Task::getType))
                .forEach(t -> taskType.put(t.getId(), t.getType()));
        double[] typeHours = new double[TYPE_KEYS.length];
        for (Worklog w : winRows) {
            typeHours[typeIndex(w, taskType)] += h(w);
        }
        List<Map<String, Object>> byType = new ArrayList<>();
        for (int i = 0; i < TYPE_KEYS.length; i++) {
            int pct = winSum <= 0 ? 0 : (int) Math.round(typeHours[i] * 100 / winSum);
            byType.add(Map.of("k", TYPE_KEYS[i], "pct", pct, "color", TYPE_COLORS[i]));
        }

        // ===== daily（4 天，止于最新记录日/今天）=====
        Worklog latest = worklogMapper.selectOne(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, selfId)
                .select(Worklog::getWorkDate)
                .orderByDesc(Worklog::getWorkDate)
                .last("LIMIT 1"));
        LocalDate latestDay = latest == null ? null : Days.parse(latest.getWorkDate());
        LocalDate dailyEnd = latestDay == null || latestDay.isAfter(today) ? today : latestDay;
        LocalDate dailyStart = dailyEnd.minusDays(3);
        Map<String, Double> byDay = new HashMap<>();
        worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                        .eq(Worklog::getProjectId, pid)
                        .eq(!team, Worklog::getMemberId, selfId)
                        .ge(Worklog::getWorkDate, dailyStart.toString())
                        .lt(Worklog::getWorkDate, dailyEnd.plusDays(1).toString())
                        .select(Worklog::getWorkDate, Worklog::getHours))
                .forEach(w -> byDay.merge(dayOf(w), h(w), Double::sum));
        List<String> days = new ArrayList<>();
        List<Double> hours = new ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            String d = dailyEnd.minusDays(i).toString();
            days.add(d);
            hours.add(r1(byDay.getOrDefault(d, 0.0)));
        }

        // ===== heat（近 14 天，成员 × 日期，服务端聚合，与明细分页无关）=====
        LocalDate heatStart = today.minusDays(HEAT_DAYS - 1);
        Map<Long, Map<String, Double>> heatMap = new HashMap<>();
        worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                        .eq(Worklog::getProjectId, pid)
                        .eq(!team, Worklog::getMemberId, selfId)
                        .ge(Worklog::getWorkDate, heatStart.toString())
                        .lt(Worklog::getWorkDate, today.plusDays(1).toString())
                        .select(Worklog::getMemberId, Worklog::getWorkDate, Worklog::getHours))
                .forEach(w -> heatMap.computeIfAbsent(w.getMemberId(), k -> new HashMap<>())
                        .merge(dayOf(w), h(w), Double::sum));
        List<String> heatDays = new ArrayList<>();
        for (int i = HEAT_DAYS - 1; i >= 0; i--) heatDays.add(today.minusDays(i).toString());
        List<Map<String, Object>> heatRows = new ArrayList<>();
        for (Long id : memberIds) {
            Map<String, Double> byDate = heatMap.getOrDefault(id, Map.of());
            List<Double> cells = heatDays.stream().map(d -> r1(byDate.getOrDefault(d, 0.0))).toList();
            double total = r1(cells.stream().mapToDouble(Double::doubleValue).sum());
            heatRows.add(Map.of("id", id, "cells", cells, "total", total));
        }
        Map<String, Object> heat = new LinkedHashMap<>();
        heat.put("days", heatDays);
        heat.put("rows", heatRows);

        // ===== records 分页（日期 + 单号倒序，DB LIMIT/OFFSET）=====
        int pages = (int) Math.max(1, (allCount + size - 1) / size);
        int p = Math.min(Math.max(1, pageNo), pages);
        int offset = (p - 1) * size;
        List<Worklog> pageRows = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, selfId)
                .orderByDesc(Worklog::getWorkDate)
                .orderByDesc(Worklog::getId)
                .last("LIMIT " + size + " OFFSET " + offset));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Worklog w : pageRows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", w.getId());
            r.put("who", w.getMemberId());
            r.put("date", w.getWorkDate());
            r.put("kind", kindOf(w));
            r.put("refId", refIdOf(w));
            r.put("task", w.getRefText());
            r.put("hours", h(w));
            r.put("note", w.getNote() == null ? "" : w.getNote());
            rows.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", team ? "team" : "self");
        out.put("iter", active == null ? "" : active.getCode());
        out.put("window", Map.of("start", winStart.toString(), "end", winEnd.toString()));
        out.put("summary", summary);
        out.put("byMember", byMember);
        out.put("byType", byType);
        out.put("daily", Map.of("days", days, "hours", hours));
        out.put("heat", heat);
        out.put("records", PageResult.of(rows, allCount, p, size));
        return out;
    }

    // ===== GET /worklog/export（全量 CSV，UTF-8 BOM）=====

    public Csv export() {
        Long pid = projectContext.currentProjectId();
        MemberPrincipal me = AuthContext.require();
        boolean team = me.admin() || isOwner(pid, me.id());
        Iteration active = activeIteration(pid);
        List<Worklog> rows = worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, me.id())
                .orderByDesc(Worklog::getWorkDate)
                .orderByDesc(Worklog::getId));
        List<Long> ids = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, pid))
                .stream().map(ProjectMember::getMemberId).toList();
        Map<Long, Member> members = membersOf(ids);

        StringBuilder sb = new StringBuilder("\uFEFF单号,成员姓名,日期,关联类型,关联编号,关联对象,工时,备注\r\n");
        for (Worklog w : rows) {
            Member m = members.get(w.getMemberId());
            String kind = kindOf(w);
            sb.append(csv(w.getId())).append(',')
                    .append(csv(m == null ? String.valueOf(w.getMemberId()) : m.getName())).append(',')
                    .append(csv(dayOf(w))).append(',')
                    .append(csv(kindLabel(kind))).append(',')
                    .append(csv(refIdOf(w))).append(',')
                    .append(csv(w.getRefText())).append(',')
                    .append(r1(h(w))).append(',')
                    .append(csv(w.getNote())).append("\r\n");
        }
        String code = active == null ? "all" : active.getCode();
        return new Csv("worklog-" + code + ".csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public record Csv(String filename, byte[] data) {
    }

    // ===== POST /worklog =====

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Long who = logOwner(body);
        LogInput in = parseInput(body, null);
        ensureDayCap(who, in.day(), in.hours(), null);

        Worklog w = new Worklog();
        w.setId(sequence.next("worklog"));
        w.setProjectId(projectContext.currentProjectId());
        w.setMemberId(who);
        w.setWorkDate(in.day() + " 00:00:00");
        w.setRefKind(in.link().kind());
        w.setRefId(in.link().id());
        w.setRefText(in.link().text());
        w.setHours(in.hours());
        w.setNote(in.note());
        worklogMapper.insert(w);

        adjustSpent(in.link(), in.hours());
        writeActivity("登记工时", in.hours(), in.link().text(), "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", w.getId());
        return out;
    }

    // ===== PUT /worklog/{id} =====

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> body) {
        Worklog old = requireRecord(id);
        requireSelfOrManager(old);
        LogInput in = parseInput(body, old);
        ensureDayCap(old.getMemberId(), in.day(), in.hours(), old.getId());

        Link before = linkOf(old);
        if (before.kind().equals(in.link().kind()) && Objects.equals(before.id(), in.link().id())) {
            adjustSpent(in.link(), in.hours() - h(old));
        } else {
            adjustSpent(before, -h(old));
            adjustSpent(in.link(), in.hours());
        }

        // set(...) 显式写入 null：关联切换到「其他」时旧的 ref_id 必须被清空
        worklogMapper.update(null, new LambdaUpdateWrapper<Worklog>()
                .eq(Worklog::getId, id)
                .set(Worklog::getWorkDate, in.day() + " 00:00:00")
                .set(Worklog::getRefKind, in.link().kind())
                .set(Worklog::getRefId, in.link().id())
                .set(Worklog::getRefText, in.link().text())
                .set(Worklog::getHours, in.hours())
                .set(Worklog::getNote, in.note()));

        writeActivity("修改工时", in.hours(), in.link().text(), "");
        return Map.of("ok", true, "id", id);
    }

    // ===== DELETE /worklog/{id} =====

    @Transactional
    public Map<String, Object> remove(String id) {
        Worklog old = requireRecord(id);
        requireSelfOrManager(old);
        worklogMapper.deleteById(id);
        adjustSpent(linkOf(old), -h(old));
        writeActivity("删除工时", h(old), old.getRefText(), "");
        return Map.of("ok", true, "id", id);
    }

    // ===== POST /worklog/batch（一周多天，共享关联对象）=====

    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> batch(Map<String, Object> body) {
        Long who = logOwner(body);
        Object rawDays = body.get("days");
        if (!(rawDays instanceof List<?> list) || list.isEmpty()) {
            throw BizException.bad("请填写至少一天的工时");
        }
        if (list.size() > MAX_BATCH_DAYS) {
            throw BizException.bad("一次最多填报 " + MAX_BATCH_DAYS + " 天");
        }

        String sharedKind = trimToNull(Patch.str(body, "kind"));
        if (sharedKind == null) sharedKind = trimToNull(Patch.str(body, "refKind"));
        Link shared = resolveLink(sharedKind,
                trimToNull(Patch.str(body, "refId")),
                trimToNull(Patch.str(body, "task")));
        String sharedNote = cleanNote(Patch.str(body, "note"));

        List<LogInput> inputs = new ArrayList<>();
        Map<LocalDate, Double> pending = new LinkedHashMap<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?>)) {
                throw BizException.bad("工时数据格式不正确");
            }
            Map<String, Object> item = (Map<String, Object>) raw;
            LocalDate day = requiredDay(Patch.str(item, "date"));
            double hours = normalizeHours(Patch.dbl(item, "hours"));
            String itemNote = Patch.str(item, "note");
            String note = itemNote == null ? sharedNote : cleanNote(itemNote);
            inputs.add(new LogInput(shared, hours, day, note));
            pending.merge(day, hours, Double::sum);
        }
        for (Map.Entry<LocalDate, Double> e : pending.entrySet()) {
            double used = usedHours(who, e.getKey(), null);
            if (used + e.getValue() > MAX_DAY_HOURS + 0.001) {
                throw BizException.bad(e.getKey() + " 当日累计工时不能超过 " + MAX_DAY_HOURS
                        + " 小时（已登记 " + r1(used) + " h）");
            }
        }

        double total = 0;
        for (LogInput in : inputs) {
            Worklog w = new Worklog();
            w.setId(sequence.next("worklog"));
            w.setProjectId(projectContext.currentProjectId());
            w.setMemberId(who);
            w.setWorkDate(in.day() + " 00:00:00");
            w.setRefKind(in.link().kind());
            w.setRefId(in.link().id());
            w.setRefText(in.link().text());
            w.setHours(in.hours());
            w.setNote(in.note());
            worklogMapper.insert(w);
            adjustSpent(in.link(), in.hours());
            total += in.hours();
        }
        total = r1(total);

        writeActivity("批量登记工时", total, shared.text(), "（" + inputs.size() + " 天）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", inputs.size());
        out.put("hours", total);
        return out;
    }

    // ===== 内部工具 =====

    /** 工时只能登记在本项目下；已登录人即记录归属人，who 仅允许为空或本人 */
    private static Long logOwner(Map<String, Object> body) {
        Long me = AuthContext.require().id();
        Long claimed = Patch.lng(body, "who");
        if (claimed != null && !claimed.equals(me)) {
            throw BizException.forbidden("工时只能登记本人的记录");
        }
        return me;
    }

    private record Link(String kind, String id, String text) {
    }

    private record LogInput(Link link, double hours, LocalDate day, String note) {
    }

    private LogInput parseInput(Map<String, Object> body, Worklog existing) {
        Double rawHours = Patch.dbl(body, "hours");
        if (rawHours == null && existing != null) rawHours = existing.getHours();
        double hours = normalizeHours(rawHours);

        String rawDate = trimToNull(Patch.str(body, "date"));
        LocalDate fallback = existing == null ? LocalDate.now() : Days.parse(existing.getWorkDate());
        LocalDate day;
        if (rawDate == null) {
            day = fallback == null ? LocalDate.now() : fallback;
        } else {
            day = requiredDay(rawDate);
        }

        String kind = trimToNull(Patch.str(body, "kind"));
        if (kind == null) kind = trimToNull(Patch.str(body, "refKind"));
        String refId = trimToNull(Patch.str(body, "refId"));
        String text = trimToNull(Patch.str(body, "task"));
        Link link = (kind == null && refId == null && text == null && existing != null)
                ? linkOf(existing)
                : resolveLink(kind, refId, text);

        String rawNote = Patch.str(body, "note");
        String note = (rawNote == null && existing != null) ? existing.getNote() : cleanNote(rawNote);
        return new LogInput(link, hours, day, note);
    }

    /** 显式关联优先；旧客户端只传 task 文本时按 T-xxx / B-xxx 前缀识别 */
    private Link resolveLink(String kind, String refId, String text) {
        Long pid = projectContext.currentProjectId();
        String k = kind == null ? null : kind.trim().toLowerCase(Locale.ROOT);
        String rid = refId == null ? null : refId.trim();
        String raw = text == null ? null : text.trim();

        if (!KIND_TASK.equals(k) && !KIND_DEFECT.equals(k) && !KIND_OTHER.equals(k)) k = null;
        if (k == null && rid != null) {
            if (rid.startsWith("T-")) k = KIND_TASK;
            else if (rid.startsWith("B-")) k = KIND_DEFECT;
        }
        // 仅在未显式指定类型时按文本前缀推断；显式「其他」不会被编号前缀改写
        if (k == null && rid == null && raw != null) {
            Matcher mt = TASK_REF.matcher(raw);
            if (mt.find()) {
                k = KIND_TASK;
                rid = mt.group(1);
            } else {
                Matcher md = DEFECT_REF.matcher(raw);
                if (md.find()) {
                    k = KIND_DEFECT;
                    rid = md.group(1);
                }
            }
        }

        if (KIND_TASK.equals(k)) {
            if (rid == null || rid.isBlank()) throw BizException.bad("请选择关联任务");
            Task t = taskMapper.selectById(rid);
            if (t == null || !pid.equals(t.getProjectId())) {
                throw BizException.bad("关联任务不存在或不属于当前项目");
            }
            return new Link(KIND_TASK, t.getId(), clip(t.getId() + " " + t.getTitle()));
        }
        if (KIND_DEFECT.equals(k)) {
            if (rid == null || rid.isBlank()) throw BizException.bad("请选择关联缺陷");
            Defect d = defectMapper.selectById(rid);
            if (d == null || !pid.equals(d.getProjectId())) {
                throw BizException.bad("关联缺陷不存在或不属于当前项目");
            }
            return new Link(KIND_DEFECT, d.getId(), clip(d.getId() + " " + d.getTitle()));
        }
        if (raw == null || raw.isBlank()) {
            throw BizException.bad("请选择关联任务/缺陷，或填写工时内容");
        }
        return new Link(KIND_OTHER, null, clip(raw));
    }

    /** 任务/缺陷的已投入工时增减，最低归零（历史数据可能没有对应回写） */
    private void adjustSpent(Link link, double delta) {
        if (link == null || link.id() == null || Math.abs(delta) < 0.05) return;
        String expr = String.format(Locale.ROOT, "%+.1f", delta);
        if (KIND_TASK.equals(link.kind())) {
            taskMapper.update(null, new LambdaUpdateWrapper<Task>()
                    .eq(Task::getId, link.id())
                    .setSql("spent = GREATEST(0, spent + " + expr + ")"));
        } else if (KIND_DEFECT.equals(link.kind())) {
            defectMapper.update(null, new LambdaUpdateWrapper<Defect>()
                    .eq(Defect::getId, link.id())
                    .setSql("spent = GREATEST(0, spent + " + expr + ")"));
        }
    }

    /** 单日累计（同一成员跨项目）不得超过 24h；编辑时排除自身 */
    private void ensureDayCap(Long memberId, LocalDate day, double addHours, String excludeId) {
        double used = usedHours(memberId, day, excludeId);
        if (used + addHours > MAX_DAY_HOURS + 0.001) {
            throw BizException.bad(day + " 当日累计工时不能超过 " + MAX_DAY_HOURS
                    + " 小时（已登记 " + r1(used) + " h）");
        }
    }

    private double usedHours(Long memberId, LocalDate day, String excludeId) {
        LambdaQueryWrapper<Worklog> q = new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getMemberId, memberId)
                .ge(Worklog::getWorkDate, day.toString())
                .lt(Worklog::getWorkDate, day.plusDays(1).toString())
                .select(Worklog::getHours);
        if (excludeId != null) q.ne(Worklog::getId, excludeId);
        return sum(worklogMapper.selectList(q));
    }

    private Worklog requireRecord(String id) {
        Worklog w = worklogMapper.selectById(id);
        if (w == null || !projectContext.currentProjectId().equals(w.getProjectId())) {
            throw BizException.notFound("工时记录不存在");
        }
        return w;
    }

    /** 系统管理员、项目负责人或记录本人可编辑/删除 */
    private void requireSelfOrManager(Worklog w) {
        MemberPrincipal me = AuthContext.require();
        if (me.admin() || me.id().equals(w.getMemberId()) || isOwner(w.getProjectId(), me.id())) return;
        throw BizException.forbidden("只能修改本人登记的工时");
    }

    private boolean isOwner(Long pid, Long memberId) {
        Project p = projectMapper.selectById(pid);
        return p != null && memberId.equals(p.getOwner());
    }

    private void writeActivity(String action, double hours, String refText, String suffix) {
        MemberPrincipal me = AuthContext.require();
        Member m = memberMapper.selectById(me.id());
        String name = m != null ? m.getName() : me.name();
        activityLog.write("<b>" + esc(name) + "</b> " + action
                + " <span class=\"mr\">" + hoursText(hours) + "h</span> · "
                + esc(refText == null ? "" : refText) + suffix);
    }

    private static double normalizeHours(Double raw) {
        if (raw == null || raw.isNaN() || raw.isInfinite()) throw BizException.bad("请填写工时");
        double h = Math.round(raw * 10) / 10.0;
        if (h < 0.1 || h > MAX_DAY_HOURS) {
            throw BizException.bad("工时需在 0.1 ~ 24 小时之间");
        }
        return h;
    }

    private static LocalDate requiredDay(String raw) {
        if (raw == null || raw.isBlank()) throw BizException.bad("请选择日期");
        LocalDate d = Days.parse(raw);
        if (d == null) throw BizException.bad("日期格式不正确");
        if (d.isAfter(LocalDate.now())) throw BizException.bad("不能登记未来日期的工时");
        return d;
    }

    private static String cleanNote(String note) {
        if (note == null) return null;
        String n = note.trim();
        if (n.isEmpty()) return null;
        return n.length() > 255 ? n.substring(0, 255) : n;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String v) {
        String s = v == null ? "" : v.trim();
        return s.length() > 128 ? s.substring(0, 128) : s;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private Iteration activeIteration(Long pid) {
        return iterationMapper.selectOne(new LambdaQueryWrapper<Iteration>()
                .eq(Iteration::getProjectId, pid).eq(Iteration::getState, "active")
                .orderByDesc(Iteration::getCode).last("LIMIT 1"));
    }

    private Map<Long, Member> membersOf(List<Long> ids) {
        Map<Long, Member> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        memberMapper.selectBatchIds(ids).forEach(m -> out.put(m.getId(), m));
        return out;
    }

    private double sumBetween(Long pid, LocalDate from, LocalDate toInclusive, boolean team, Long selfId) {
        return worklogMapper.selectList(new LambdaQueryWrapper<Worklog>()
                        .eq(Worklog::getProjectId, pid)
                        .eq(!team, Worklog::getMemberId, selfId)
                        .ge(Worklog::getWorkDate, from.toString())
                        .lt(Worklog::getWorkDate, toInclusive.plusDays(1).toString())
                        .select(Worklog::getHours))
                .stream().mapToDouble(WorklogService::h).sum();
    }

    private long countBetween(Long pid, LocalDate from, LocalDate toInclusive, boolean team, Long selfId) {
        return worklogMapper.selectCount(new LambdaQueryWrapper<Worklog>()
                .eq(Worklog::getProjectId, pid)
                .eq(!team, Worklog::getMemberId, selfId)
                .ge(Worklog::getWorkDate, from.toString())
                .lt(Worklog::getWorkDate, toInclusive.plusDays(1).toString()));
    }

    private static int cap(Member m) {
        return m.getCapacity() == null || m.getCapacity() <= 0 ? DEFAULT_CAPACITY : m.getCapacity();
    }

    private static String kindOf(Worklog w) {
        String k = w.getRefKind();
        if (KIND_TASK.equals(k) || KIND_DEFECT.equals(k) || KIND_OTHER.equals(k)) return k;
        String text = w.getRefText() == null ? "" : w.getRefText();
        if (TASK_REF.matcher(text).find()) return KIND_TASK;
        if (DEFECT_REF.matcher(text).find()) return KIND_DEFECT;
        return KIND_OTHER;
    }

    private static String refIdOf(Worklog w) {
        if (KIND_OTHER.equals(w.getRefKind())) return null;
        if (w.getRefId() != null && !w.getRefId().isBlank()) return w.getRefId();
        String text = w.getRefText() == null ? "" : w.getRefText();
        Matcher t = TASK_REF.matcher(text);
        if (t.find()) return t.group(1);
        Matcher d = DEFECT_REF.matcher(text);
        return d.find() ? d.group(1) : null;
    }

    private static Link linkOf(Worklog w) {
        return new Link(kindOf(w), refIdOf(w), w.getRefText() == null ? "" : w.getRefText());
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case KIND_TASK -> "任务";
            case KIND_DEFECT -> "缺陷";
            default -> "其他";
        };
    }

    /** 窗口工时按关联类型归类：任务→工种（fe/be/qa/de），缺陷→缺陷修复，其余→运维 / 其他 */
    private static int typeIndex(Worklog w, Map<String, String> taskType) {
        String kind = kindOf(w);
        if (KIND_DEFECT.equals(kind)) return 4;
        if (KIND_TASK.equals(kind)) {
            String type = taskType.get(refIdOf(w));
            if ("fe".equals(type)) return 0;
            if ("be".equals(type)) return 1;
            if ("qa".equals(type)) return 2;
            if ("de".equals(type)) return 3;
        }
        return 5;
    }

    private static String csv(String v) {
        String s = v == null ? "" : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String dayOf(Worklog w) {
        String d = w.getWorkDate();
        return d == null || d.length() < 10 ? "" : d.substring(0, 10);
    }

    private static double h(Worklog w) {
        return w.getHours() == null ? 0 : w.getHours();
    }

    private static double sum(List<Worklog> rows) {
        return rows.stream().mapToDouble(WorklogService::h).sum();
    }

    private static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String hoursText(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }
}
