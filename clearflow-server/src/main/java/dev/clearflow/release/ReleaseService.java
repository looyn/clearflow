package dev.clearflow.release;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.collab.CollabService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.Jsonz;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Patch;
import dev.clearflow.common.Times;
import dev.clearflow.defect.Defect;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.infra.SequenceService;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.notify.NotificationService;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 发版计划 CRUD + 状态机（状态流来自项目设置 → 工作流 → 发版计划工作流）+ 关联内容 + 邮件内容生成（只生成不发送） */
@Service
public class ReleaseService {

    private final ReleaseMapper mapper;
    private final SequenceService sequence;
    private final CollabService collab;
    private final ProjectContext projectContext;
    private final ActivityLog activityLog;
    private final RequirementMapper requirementMapper;
    private final DefectMapper defectMapper;
    private final MemberMapper memberMapper;
    private final NotificationService notificationService;
    private final SettingsService settings;

    public ReleaseService(ReleaseMapper mapper, SequenceService sequence, CollabService collab,
                          ProjectContext projectContext, ActivityLog activityLog,
                          RequirementMapper requirementMapper, DefectMapper defectMapper,
                          MemberMapper memberMapper, NotificationService notificationService,
                          SettingsService settings) {
        this.mapper = mapper;
        this.sequence = sequence;
        this.collab = collab;
        this.projectContext = projectContext;
        this.activityLog = activityLog;
        this.requirementMapper = requirementMapper;
        this.defectMapper = defectMapper;
        this.memberMapper = memberMapper;
        this.notificationService = notificationService;
        this.settings = settings;
    }

    public Release get(String id) {
        Release r = mapper.selectById(id);
        if (r == null || !r.getProjectId().equals(projectContext.currentProjectId())) {
            throw BizException.notFound("发版计划不存在或不属于当前项目");
        }
        return r;
    }

    /** 发版计划列表（服务端分页，新在前，与 mock unshift 语义一致） */
    public PageResult<Release.View> page(int page, int pageSize, String status, String owner, String q) {
        Long projectId = projectContext.currentProjectId();
        Page<Release> result = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 200)),
                filtered(projectId, status, owner, q).select(LIST_COLUMNS).orderByDesc("id"));
        return PageResult.of(listViews(result.getRecords()), result.getTotal(),
                (int) result.getCurrent(), (int) result.getSize());
    }

    /** 列表行不含 notes 富文本正文（详情接口单独下发） */
    private static final String[] LIST_COLUMNS = {
            "id", "project_id", "version", "name", "status", "rel_date", "owner", "channel",
            "risk", "req_ids", "defect_ids", "created", "updated"
    };

    private QueryWrapper<Release> filtered(Long projectId, String status, String owner, String q) {
        QueryWrapper<Release> w = new QueryWrapper<Release>().eq("project_id", projectId);
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            w.eq("status", status.trim());
        }
        Long ownerId = parseId(owner, "负责人");
        if (ownerId != null) {
            w.eq("owner", ownerId);
        }
        String like = like(q);
        if (like != null) {
            w.and(x -> x.like("version", like).or().like("name", like).or().like("id", like));
        }
        return w;
    }

    /** 列表视图：不含关注 / 附件 / 动态（详情接口才装配这些） */
    private List<Release.View> listViews(List<Release> rows) {
        return rows.stream().map(r -> Release.View.of(r, List.of(), List.of(), List.of())).toList();
    }

    public List<Release.View> views(List<Release> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Long projectId = rows.get(0).getProjectId();
        List<String> ids = rows.stream().map(Release::getId).toList();
        CollabService.Bundle bundle = collab.expand("release", ids, projectId);
        return rows.stream()
                .map(r -> Release.View.of(r,
                        bundle.watchers().getOrDefault(r.getId(), List.of()),
                        bundle.attachments().getOrDefault(r.getId(), List.of()),
                        bundle.activity().getOrDefault(r.getId(), List.of())))
                .toList();
    }

    public Release.View view(String id) {
        return views(List.of(get(id))).get(0);
    }

    /** 状态统计条：计数按项目配置的状态生成（含特有状态 rolled），一次 GROUP BY 完成 */
    public Map<String, Object> stats() {
        Long projectId = projectContext.currentProjectId();
        Map<String, Long> byStatus = new HashMap<>();
        long total = 0;
        for (Map<String, Object> row : mapper.selectMaps(new QueryWrapper<Release>()
                .select("status", "COUNT(*) AS cnt").eq("project_id", projectId).groupBy("status"))) {
            Object k = row.get("status");
            Object v = row.get("cnt");
            if (k != null && v != null) {
                byStatus.put(String.valueOf(k), ((Number) v).longValue());
                total += ((Number) v).longValue();
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        for (String key : settings.flow(projectId, "relColumns")) {
            counts.put(key, byStatus.getOrDefault(key, 0L));
        }
        counts.putIfAbsent("rolled", byStatus.getOrDefault("rolled", 0L)); // 特有状态也统计

        Map<String, Object> out = new LinkedHashMap<>(counts);
        // 旧固定 key（默认配置下与 counts 等值），供未改造的调用点回退
        out.put("planning", byStatus.getOrDefault("planning", 0L));
        out.put("testing", byStatus.getOrDefault("testing", 0L));
        out.put("ready", byStatus.getOrDefault("ready", 0L));
        out.put("released", byStatus.getOrDefault("released", 0L));
        out.put("rolled", byStatus.getOrDefault("rolled", 0L));
        out.put("total", total);
        out.put("counts", counts);
        return out;
    }

    /** 已关联的发布内容（需求 / 缺陷）：只取展示字段，一次批量查，不受列表分页影响 */
    public Map<String, Object> linked(String id) {
        Release r = get(id);
        List<Requirement> reqRows = batchRequirements(r);
        List<Defect> defRows = batchDefects(r);
        Map<Long, String> names = memberNames(reqRows, defRows);
        List<Map<String, Object>> reqs = reqRows.stream()
                .map(x -> linkedItem(x.getId(), x.getTitle(), x.getStatus(), x.getOwner(), names))
                .toList();
        List<Map<String, Object>> defects = defRows.stream()
                .map(d -> linkedItem(d.getId(), d.getTitle(), d.getStatus(), d.getOwner(), names))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reqs", reqs);
        out.put("defects", defects);
        return out;
    }

    private static Map<String, Object> linkedItem(String id, String title, String status, Long owner,
                                                  Map<Long, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("status", status);
        m.put("owner", owner);
        m.put("ownerName", owner == null ? "—" : names.getOrDefault(owner, "—"));
        return m;
    }

    /** 关联内容涉及的处理人姓名（需求负责人 + 缺陷处理人），一次批量查 */
    private Map<Long, String> memberNames(List<Requirement> reqs, List<Defect> defects) {
        List<Long> ids = new ArrayList<>();
        reqs.forEach(x -> {
            if (x.getOwner() != null) ids.add(x.getOwner());
        });
        defects.forEach(d -> {
            if (d.getOwner() != null) ids.add(d.getOwner());
        });
        Map<Long, String> out = new HashMap<>();
        if (!ids.isEmpty()) {
            memberMapper.selectBatchIds(ids.stream().distinct().toList())
                    .forEach(m -> out.put(m.getId(), m.getName()));
        }
        return out;
    }

    /** 关联候选（需求）：服务端搜索 + 分页，已关联的标记 linked 供前端置灰 */
    public PageResult<Map<String, Object>> reqOptions(String id, String q, int page, int pageSize) {
        Release r = get(id);
        Set<String> linkedIds = new LinkedHashSet<>(Jsonz.stringList(r.getReqIds()));
        Page<Requirement> result = requirementMapper.selectPage(
                new Page<>(Math.max(1, page), size(pageSize)),
                optionsQuery(r.getProjectId(), q));
        List<Map<String, Object>> list = result.getRecords().stream()
                .map(x -> {
                    Map<String, Object> m = option(x.getId(), x.getTitle(), x.getStatus());
                    m.put("linked", linkedIds.contains(x.getId()));
                    return m;
                })
                .toList();
        return PageResult.of(list, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /** 关联候选（缺陷）：同上 */
    public PageResult<Map<String, Object>> defectOptions(String id, String q, int page, int pageSize) {
        Release r = get(id);
        Set<String> linkedIds = new LinkedHashSet<>(Jsonz.stringList(r.getDefectIds()));
        Page<Defect> result = defectMapper.selectPage(
                new Page<>(Math.max(1, page), size(pageSize)),
                optionsQuery(r.getProjectId(), q));
        List<Map<String, Object>> list = result.getRecords().stream()
                .map(d -> {
                    Map<String, Object> m = option(d.getId(), d.getTitle(), d.getStatus());
                    m.put("linked", linkedIds.contains(d.getId()));
                    return m;
                })
                .toList();
        return PageResult.of(list, result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    private static Map<String, Object> option(String id, String title, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("status", status);
        return m;
    }

    private static int size(int pageSize) {
        return Math.min(Math.max(1, pageSize), 100);
    }

    private static <T> QueryWrapper<T> optionsQuery(Long projectId, String q) {
        QueryWrapper<T> w = new QueryWrapper<>();
        w.eq("project_id", projectId);
        String like = like(q);
        if (like != null) {
            w.and(x -> x.like("id", like).or().like("title", like));
        }
        return w.select("id", "title", "status").orderByDesc("id");
    }

    /** LIKE 模式：% 与 _ 转义，避免用户输入的通配符扩大匹配范围 */
    private static String like(String q) {
        String v = q == null || q.isBlank() ? null : q.trim();
        return v == null ? null : '%' + v.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + '%';
    }

    private static Long parseId(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.bad(label + "参数不合法：" + raw);
        }
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        String version = Patch.str(body, "version");
        String name = Patch.str(body, "name");
        if (version == null || version.isBlank()) {
            throw BizException.bad("版本号不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        MemberPrincipal actor = AuthContext.require();
        String id = sequence.next("release");

        Release r = new Release();
        r.setId(id);
        r.setProjectId(projectId);
        r.setVersion(version.trim());
        r.setName(name == null ? "" : name.trim());
        String status = Patch.str(body, "status");
        List<String> flow = settings.flow(projectId, "relColumns"); // 项目发版工作流的合法状态
        r.setStatus(status == null || (!flow.contains(status) && !"rolled".equals(status))
                ? flow.get(0) : status);
        r.setDate(Patch.str(body, "date"));
        r.setOwner(Patch.lng(body, "owner"));
        r.setChannel(Patch.str(body, "channel"));
        r.setRisk(Patch.str(body, "risk"));
        r.setNotes(Patch.str(body, "notes"));
        r.setReqIds(jsonList(Patch.strs(body, "reqIds")));
        r.setDefectIds(jsonList(Patch.strs(body, "defectIds")));
        r.setCreated(Times.now());
        r.setUpdated("刚刚");
        mapper.insert(r);

        collab.push("release", projectId, id, "create",
                "创建发版计划 " + r.getVersion() + " " + r.getName());
        activityLog.write("<b>" + actor.name() + "</b> 创建发版计划 <span class=\"mr\">"
                + r.getVersion() + "</span>「" + r.getName() + "」");
        return Map.of("ok", true, "id", id);
    }

    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> patch) {
        Release r = get(id);

        if (patch.containsKey("status")) {
            checkTransition(r, Patch.str(patch, "status"));
        }
        // 关键字段旧→新动态（状态/负责人/日期/风险/渠道）
        collab.changes("release", r.getProjectId(), id, before(r), patch);

        // 关联与说明变更写入操作记录（与 mock 逐字一致）
        if (patch.get("reqIds") instanceof List<?> list && list.size() != Jsonz.stringList(r.getReqIds()).size()) {
            collab.push("release", r.getProjectId(), id, "field",
                    "将关联需求调整为 " + list.size() + " 项");
        }
        if (patch.get("defectIds") instanceof List<?> list && list.size() != Jsonz.stringList(r.getDefectIds()).size()) {
            collab.push("release", r.getProjectId(), id, "field",
                    "将关联缺陷调整为 " + list.size() + " 项");
        }
        if (patch.containsKey("notes")) {
            String notes = Patch.str(patch, "notes");
            String old = r.getNotes() == null ? "" : r.getNotes();
            if (!String.valueOf(notes == null ? "" : notes).equals(old)) {
                collab.push("release", r.getProjectId(), id, "field",
                        notes == null || notes.isBlank() ? "清空了发版说明" : "更新了发版说明");
            }
        }

        if (patch.containsKey("version")) r.setVersion(Patch.str(patch, "version"));
        if (patch.containsKey("name")) r.setName(Patch.str(patch, "name"));
        if (patch.containsKey("status")) r.setStatus(Patch.str(patch, "status"));
        if (patch.containsKey("date")) r.setDate(Patch.str(patch, "date"));
        if (patch.containsKey("owner")) r.setOwner(Patch.lng(patch, "owner"));
        if (patch.containsKey("channel")) r.setChannel(Patch.str(patch, "channel"));
        if (patch.containsKey("risk")) r.setRisk(Patch.str(patch, "risk"));
        if (patch.containsKey("notes")) r.setNotes(Patch.str(patch, "notes"));
        if (patch.get("reqIds") instanceof List<?>) r.setReqIds(jsonList(Patch.strs(patch, "reqIds")));
        if (patch.get("defectIds") instanceof List<?>) r.setDefectIds(jsonList(Patch.strs(patch, "defectIds")));
        r.setUpdated("刚刚");
        mapper.updateById(r);

        // 发版计划状态变更时通知负责人（按负责人自己的 release 开关判断；push 内部跳过自己）
        if (patch.containsKey("status") && r.getOwner() != null) {
            notificationService.push(r.getOwner(), "release",
                    "发版计划 " + r.getVersion() + "「" + r.getName() + "」状态变更为 "
                            + settings.statusName(r.getProjectId(), "relColumns", Patch.str(patch, "status")),
                    "release", "release", r.getId());
        }
        return Map.of("ok", true);
    }

    /** 发版状态展示名：配置名 → 特有状态 rolled「已回滚」 → key */
    private String statusName(Long projectId, String status) {
        if (status == null) {
            return "—";
        }
        return settings.statusName(projectId, "relColumns", status);
    }

    /** 配置顺序单向流转（只进不退）；「已回滚」为特有状态，仅允许从终态（默认 released）发起 */
    private void checkTransition(Release r, String next) {
        List<String> flow = settings.flow(r.getProjectId(), "relColumns");
        if (next == null || (!flow.contains(next) && !"rolled".equals(next))) {
            throw BizException.bad("非法发版状态：" + next);
        }
        String end = flow.get(flow.size() - 1); // 终态，默认 released
        if ("rolled".equals(next)) {
            if (!end.equals(r.getStatus())) {
                throw BizException.bad("仅" + statusName(r.getProjectId(), end) + "的计划可回滚");
            }
            return;
        }
        if (flow.indexOf(next) < flow.indexOf(r.getStatus())) {
            throw BizException.bad("发版状态不可回退");
        }
    }

    private Map<String, Object> before(Release r) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", r.getStatus());
        m.put("owner", r.getOwner());
        m.put("date", r.getDate());
        m.put("risk", r.getRisk());
        m.put("channel", r.getChannel());
        return m;
    }

    @Transactional
    public Map<String, Object> remove(String id) {
        Release r = get(id);
        mapper.deleteById(id);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 删除发版计划 <span class=\"mr\">"
                + (r.getVersion() == null ? id : r.getVersion()) + "</span>「"
                + (r.getName() == null ? "" : r.getName()) + "」");
        return Map.of("ok", true);
    }

    // ===== 邮件内容生成（只生成不发送，与 ReleasePlanView mail 计算属性同构） =====

    public Map<String, Object> mail(String id) {
        Release r = get(id);
        List<Requirement> reqs = batchRequirements(r);
        List<Defect> defects = batchDefects(r);

        Set<Long> ownerIds = new LinkedHashSet<>();
        reqs.forEach(x -> {
            if (x.getOwner() != null) ownerIds.add(x.getOwner());
        });
        defects.forEach(x -> {
            if (x.getOwner() != null) ownerIds.add(x.getOwner());
        });
        if (r.getOwner() != null) {
            ownerIds.add(r.getOwner());
        }

        Map<Long, Member> members = new HashMap<>();
        ownerIds.forEach(mid -> {
            Member m = memberMapper.selectById(mid);
            if (m != null) {
                members.put(mid, m);
            }
        });

        // 邮箱属于成员管理字段，只有系统管理员可读（与 GET /members 同口径）：
        // 其他角色照样能生成邮件正文，但收件人为空，前端据此提示无读取权限
        boolean canSeeEmail = AuthContext.require().admin();
        List<String> to = canSeeEmail
                ? ownerIds.stream()
                        .map(members::get)
                        .filter(m -> m != null && m.getEmail() != null && !m.getEmail().isBlank())
                        .map(Member::getEmail)
                        .distinct()
                        .toList()
                : List.of();

        String date = r.getDate() == null ? "" : (r.getDate().length() > 5 ? r.getDate().substring(5) : r.getDate());
        String subject = "【发版通知】" + r.getVersion() + " " + r.getName() + " 计划于 " + date + " 发布";

        java.util.function.Function<Long, String> name =
                mid -> members.get(mid) == null ? "—" : members.get(mid).getName();
        List<String> reqLines = reqs.stream()
                .map(x -> "- " + x.getId() + " " + x.getTitle() + "（" + name.apply(x.getOwner()) + "）")
                .toList();
        List<String> defLines = defects.stream()
                .map(x -> "- " + x.getId() + " " + x.getTitle() + "（" + name.apply(x.getOwner()) + "）")
                .toList();

        String ownerName = name.apply(r.getOwner());
        Member owner = r.getOwner() == null ? null : members.get(r.getOwner());
        String ownerMail = !canSeeEmail || owner == null || owner.getEmail() == null
                ? "" : "（" + owner.getEmail() + "）";

        List<String> lines = new ArrayList<>();
        lines.add("各位好，");
        lines.add("");
        lines.add("版本 " + r.getVersion() + "「" + r.getName() + "」计划于 " + r.getDate()
                + " 发布，发布渠道：" + r.getChannel() + "，风险等级：" + r.getRisk() + "。");
        lines.add("");
        lines.add("一、新增功能");
        lines.addAll(reqLines.isEmpty() ? List.of("（无）") : reqLines);
        lines.add("");
        lines.add("二、问题修复");
        lines.addAll(defLines.isEmpty() ? List.of("（无）") : defLines);
        lines.add("");
        lines.add("三、注意事项");
        lines.add("- 发布前请完成回归验证，并准备好回滚方案。");
        lines.add("- 发布完成后请相关同学跟进线上验证。");
        lines.add("");
        lines.add("发版负责人：" + ownerName + ownerMail);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("to", to);
        out.put("subject", subject);
        out.put("body", String.join("\n", lines));
        return out;
    }

    private List<Requirement> batchRequirements(Release r) {
        List<String> ids = Jsonz.stringList(r.getReqIds());
        if (ids.isEmpty()) {
            return List.of();
        }
        return requirementMapper.selectBatchIds(ids);
    }

    private List<Defect> batchDefects(Release r) {
        List<String> ids = Jsonz.stringList(r.getDefectIds());
        if (ids.isEmpty()) {
            return List.of();
        }
        return defectMapper.selectBatchIds(ids);
    }

    private static String jsonList(List<String> list) {
        return Jsonz.write(list == null ? List.of() : list);
    }
}
