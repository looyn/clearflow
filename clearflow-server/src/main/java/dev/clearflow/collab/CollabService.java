package dev.clearflow.collab;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.Jsonz;
import dev.clearflow.common.Times;
import dev.clearflow.doc.DocMapper;
import dev.clearflow.file.StorageService;
import dev.clearflow.infra.ProjectContext;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberMapper;
import dev.clearflow.notify.NotificationService;
import dev.clearflow.perm.PermConstants;
import dev.clearflow.perm.PermService;
import dev.clearflow.project.Project;
import dev.clearflow.project.ProjectMapper;
import dev.clearflow.release.ReleaseMapper;
import dev.clearflow.defect.DefectMapper;
import dev.clearflow.requirement.RequirementMapper;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
import dev.clearflow.task.Task;
import dev.clearflow.task.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多态横切服务：评论 / 附件 / 关注 / 实体动态（kind + ref_id 统一挂载）
 * kind 别名归一：req ≡ requirement（存储统一为 requirement，响应回显请求原 kind）
 */
@Service
public class CollabService {

    private static final Map<String, String> KIND_MODULE = Map.of(
            "task", "任务看板",
            "defect", "缺陷追踪",
            "requirement", "需求池",
            "release", "发版计划",
            "iteration", "迭代规划",
            "doc", "文档管理");

    private static final Map<String, String> KIND_LABEL = Map.of(
            "task", "任务", "defect", "缺陷",
            "req", "需求", "requirement", "需求",
            "release", "发版计划", "doc", "文档");

    /** 关键字段标签（与前端 logFieldChanges 一致） */
    private static final Map<String, String> FIELD_LABEL = new LinkedHashMap<>() {{
        put("status", "状态");
        put("assignee", "负责人");
        put("owner", "负责人");
        put("prio", "优先级");
        put("severity", "严重度");
        put("env", "环境");
        put("iter", "迭代");
        put("date", "计划发版日期");
        put("risk", "风险等级");
        put("channel", "发布渠道");
    }};

    private final CommentMapper commentMapper;
    private final AttachmentMapper attachmentMapper;
    private final WatcherMapper watcherMapper;
    private final ActivityMapper activityMapper;
    private final TaskMapper taskMapper;
    private final RequirementMapper requirementMapper;
    private final DefectMapper defectMapper;
    private final ReleaseMapper releaseMapper;
    private final DocMapper docMapper;
    private final MemberMapper memberMapper;
    private final ProjectMapper projectMapper;
    private final PermService permService;
    private final NotificationService notificationService;
    private final StorageService storageService;
    private final ProjectContext projectContext;
    private final dev.clearflow.perm.ProjectAccessGuard accessGuard;
    private final SettingsService settingsService;

    public CollabService(CommentMapper commentMapper, AttachmentMapper attachmentMapper,
                         WatcherMapper watcherMapper, ActivityMapper activityMapper,
                         TaskMapper taskMapper, RequirementMapper requirementMapper,
                         DefectMapper defectMapper, ReleaseMapper releaseMapper, DocMapper docMapper,
                         MemberMapper memberMapper, ProjectMapper projectMapper, PermService permService,
                         NotificationService notificationService, StorageService storageService,
                         ProjectContext projectContext, dev.clearflow.perm.ProjectAccessGuard accessGuard,
                         SettingsService settingsService) {
        this.commentMapper = commentMapper;
        this.attachmentMapper = attachmentMapper;
        this.watcherMapper = watcherMapper;
        this.activityMapper = activityMapper;
        this.taskMapper = taskMapper;
        this.requirementMapper = requirementMapper;
        this.defectMapper = defectMapper;
        this.releaseMapper = releaseMapper;
        this.docMapper = docMapper;
        this.memberMapper = memberMapper;
        this.projectMapper = projectMapper;
        this.permService = permService;
        this.notificationService = notificationService;
        this.storageService = storageService;
        this.projectContext = projectContext;
        this.accessGuard = accessGuard;
        this.settingsService = settingsService;
    }

    // ===== 视图 =====

    public record AttView(String id, String name, Long size, String kind, Long by, String time, String url) {
    }

    public record ActView(String id, Long who, String type, String text, String time) {
    }

    public record Bundle(Map<String, List<Long>> watchers,
                         Map<String, List<AttView>> attachments,
                         Map<String, List<ActView>> activity) {
        public static Bundle empty() {
            return new Bundle(new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
    }

    public record CommentView(String id, String kind, String refId, String eid, Long by,
                              String at, String time, String text, List<Long> mentions) {
    }

    // ===== 工具 =====

    /** kind 归一化：req ≡ requirement */
    public static String canon(String kind) {
        return "req".equals(kind) ? "requirement" : kind;
    }

    /** kind 中文标签（评论通知文案用） */
    public static String kindLabel(String kind) {
        return KIND_LABEL.getOrDefault(canon(kind), "");
    }

    /** 多态端点的权限校验（kind → 模块动态映射；管理员/项目负责人/模块授权三层判定） */
    public void requireModule(String kind, String level) {
        String module = KIND_MODULE.get(canon(kind));
        if (module == null) {
            return; // 未知类型不做模块级限制
        }
        accessGuard.check(projectContext.currentProjectId(), module, level);
    }

    /**
     * 删除类操作的归属校验：本人，或系统管理员/项目负责人。
     * 仅补充约束，不替代 requireModule 的模块「编辑」前置校验。
     */
    private void requireSelfOrManager(Long projectId, Long creatorId) {
        MemberPrincipal me = AuthContext.require();
        if (me.admin()) {
            return;
        }
        Project p = projectMapper.selectById(projectId);
        if (p != null && me.id().equals(p.getOwner())) {
            return;
        }
        if (!me.id().equals(creatorId)) {
            throw BizException.forbidden("只能删除本人发布的内容");
        }
    }

    /** 实体存在且属于当前项目（跨项目访问返回 404） */
    public void ensure(String kind, String refId, Long projectId) {
        boolean exists = switch (canon(kind)) {
            case "task" -> taskMapper.selectCount(new LambdaQueryWrapper<Task>()
                    .eq(Task::getId, refId).eq(Task::getProjectId, projectId)) > 0;
            case "requirement" -> requirementMapper.selectCount(new LambdaQueryWrapper<dev.clearflow.requirement.Requirement>()
                    .eq(dev.clearflow.requirement.Requirement::getId, refId)
                    .eq(dev.clearflow.requirement.Requirement::getProjectId, projectId)) > 0;
            case "defect" -> defectMapper.selectCount(new LambdaQueryWrapper<dev.clearflow.defect.Defect>()
                    .eq(dev.clearflow.defect.Defect::getId, refId)
                    .eq(dev.clearflow.defect.Defect::getProjectId, projectId)) > 0;
            case "release" -> releaseMapper.selectCount(new LambdaQueryWrapper<dev.clearflow.release.Release>()
                    .eq(dev.clearflow.release.Release::getId, refId)
                    .eq(dev.clearflow.release.Release::getProjectId, projectId)) > 0;
            case "doc" -> docMapper.selectCount(new LambdaQueryWrapper<dev.clearflow.doc.Doc>()
                    .eq(dev.clearflow.doc.Doc::getId, refId)
                    .eq(dev.clearflow.doc.Doc::getProjectId, projectId)) > 0;
            default -> throw BizException.bad("不支持的实体类型：" + kind);
        };
        if (!exists) {
            throw BizException.notFound("数据不存在或不属于当前项目");
        }
    }

    /** 写一条实体动态 */
    public void push(String kind, Long projectId, String refId, String type, String text) {
        EntityActivity row = new EntityActivity();
        row.setProjectId(projectId);
        row.setKind(canon(kind));
        row.setRefId(refId);
        row.setMemberId(AuthContext.require().id());
        row.setType(type);
        row.setText(text);
        row.setCreatedAt(LocalDateTime.now());
        activityMapper.insert(row);
    }

    /**
     * 关键字段旧→新比对并写入动态（须在覆盖旧值之前调用），文本与前端 logFieldChanges 完全一致：
     * 如「将状态 待处理 → 进行中」「将优先级 P1 高 → P0 紧急122」（优先级文案取自项目设置）
     */
    public void changes(String kind, Long projectId, String refId,
                        Map<String, Object> before, Map<String, Object> patch) {
        for (Map.Entry<String, String> e : FIELD_LABEL.entrySet()) {
            String field = e.getKey();
            if (!patch.containsKey(field)) {
                continue;
            }
            Object oldValue = before.get(field);
            Object newValue = patch.get(field);
            if (norm(oldValue).equals(norm(newValue))) {
                continue;
            }
            String type = "status".equals(field) ? "status" : "field";
            String text = "将" + e.getValue() + " " + fmt(kind, field, oldValue, projectId)
                    + " → " + fmt(kind, field, newValue, projectId);
            push(kind, projectId, refId, type, text);
        }
    }

    private static String norm(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private String fmt(String kind, String field, Object value, Long projectId) {
        String s = value == null ? "" : String.valueOf(value);
        if (s.isEmpty()) {
            return "—";
        }
        if ("status".equals(field)) {
            // 状态文案统一取项目工作流配置的名称（改名/自定义状态也能显示成人话）
            String section = switch (canon(kind)) {
                case "task" -> "columns";
                case "defect" -> "defectColumns";
                case "requirement" -> "reqColumns";
                case "release" -> "relColumns";
                case "iteration" -> "iterColumns";
                default -> null;
            };
            return section == null ? s : settingsService.statusName(projectId, section, s);
        }
        if ("assignee".equals(field) || "owner".equals(field)) {
            try {
                Member m = memberMapper.selectById(Long.parseLong(s));
                return m == null ? "—" : m.getName();
            } catch (NumberFormatException e) {
                return s;
            }
        }
        if ("prio".equals(field)) {
            // 优先级文案取项目设置里的名称（自定义优先级 key 也能显示成人话）
            return settingsService.priorityLabel(projectId, s);
        }
        return s;
    }

    // ===== 附件视图 URL 解析（storage_key 存在时重新预签名） =====

    private String resolveUrl(Attachment a) {
        if (a.getStorageKey() != null && !a.getStorageKey().isBlank()) {
            try {
                String url = storageService.presign(a.getStorageKey());
                if (url != null) {
                    return url;
                }
            } catch (Exception ignored) {
                // 预签名失败回退存储的 url
            }
        }
        return a.getUrl() == null ? "" : a.getUrl();
    }

    // ===== 批量扩展（列表接口一次组装 watchers/attachments/activity） =====

    public Bundle expand(String kind, List<String> refIds, Long projectId) {
        if (refIds == null || refIds.isEmpty()) {
            return Bundle.empty();
        }
        String k = canon(kind);
        Map<String, List<Long>> watchers = new HashMap<>();
        Map<String, List<AttView>> attachments = new HashMap<>();
        Map<String, List<ActView>> activity = new HashMap<>();

        watcherMapper.selectList(new LambdaQueryWrapper<Watcher>()
                        .eq(Watcher::getProjectId, projectId)
                        .eq(Watcher::getKind, k)
                        .in(Watcher::getRefId, refIds))
                .forEach(w -> watchers.computeIfAbsent(w.getRefId(), x -> new ArrayList<>())
                        .add(w.getMemberId()));

        attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getProjectId, projectId)
                        .eq(Attachment::getEntityKind, k)
                        .in(Attachment::getRefId, refIds)
                        .orderByAsc(Attachment::getId))
                .forEach(a -> attachments.computeIfAbsent(a.getRefId(), x -> new ArrayList<>())
                        .add(new AttView("a" + a.getId(), a.getName(), a.getSize(), a.getFileType(),
                                a.getMemberId(), Times.format(a.getCreatedAt()), resolveUrl(a))));

        Map<String, Integer> seq = new HashMap<>();
        activityMapper.selectList(new LambdaQueryWrapper<EntityActivity>()
                        .eq(EntityActivity::getProjectId, projectId)
                        .eq(EntityActivity::getKind, k)
                        .in(EntityActivity::getRefId, refIds)
                        .orderByAsc(EntityActivity::getCreatedAt)
                        .orderByAsc(EntityActivity::getId))
                .forEach(row -> {
                    int n = seq.merge(row.getRefId(), 1, Integer::sum);
                    activity.computeIfAbsent(row.getRefId(), x -> new ArrayList<>())
                            .add(new ActView(row.getRefId() + "-a" + n, row.getMemberId(),
                                    row.getType(), row.getText(), Times.format(row.getCreatedAt())));
                });
        return new Bundle(watchers, attachments, activity);
    }

    // ===== 动态（升序） =====

    public List<ActView> activity(String kind, String refId, Long projectId) {
        ensure(kind, refId, projectId);
        Map<String, Integer> seq = new HashMap<>();
        List<ActView> out = new ArrayList<>();
        activityMapper.selectList(new LambdaQueryWrapper<EntityActivity>()
                        .eq(EntityActivity::getProjectId, projectId)
                        .eq(EntityActivity::getKind, canon(kind))
                        .eq(EntityActivity::getRefId, refId)
                        .orderByAsc(EntityActivity::getCreatedAt)
                        .orderByAsc(EntityActivity::getId))
                .forEach(row -> {
                    int n = seq.merge(refId, 1, Integer::sum);
                    out.add(new ActView(refId + "-a" + n, row.getMemberId(),
                            row.getType(), row.getText(), Times.format(row.getCreatedAt())));
                });
        return out;
    }

    // ===== 评论 =====

    public List<CommentView> comments(String kind, String refId, Long projectId) {
        ensure(kind, refId, projectId);
        return commentMapper.selectList(new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getProjectId, projectId)
                        .eq(Comment::getKind, canon(kind))
                        .eq(Comment::getRefId, refId)
                        .orderByAsc(Comment::getId))
                .stream()
                .map(c -> new CommentView("c" + c.getId(), kind, refId, refId, c.getMemberId(),
                        Times.format(c.getCreatedAt()), Times.format(c.getCreatedAt()),
                        c.getText(), longList(c.getMentions())))
                .toList();
    }

    /** 发表评论：按姓名识别 @提及 → 回写 mentions 并生成 mention 通知（受 notify 开关控制） */
    @Transactional
    public Map<String, Object> addComment(String kind, String refId, String text) {
        if (text == null || text.isBlank()) {
            throw BizException.bad("评论内容不能为空");
        }
        Long projectId = projectContext.currentProjectId();
        requireModule(kind, "编辑");
        ensure(kind, refId, projectId);

        MemberPrincipal actor = AuthContext.require();
        List<Long> mentions = memberMapper.selectList(null).stream()
                .filter(m -> text.contains("@" + m.getName()))
                .map(Member::getId)
                .toList();

        Comment comment = new Comment();
        comment.setProjectId(projectId);
        comment.setKind(canon(kind));
        comment.setRefId(refId);
        comment.setMemberId(actor.id());
        comment.setText(text);
        comment.setMentions(Jsonz.write(mentions));
        comment.setCreatedAt(LocalDateTime.now());
        commentMapper.insert(comment);

        push(kind, projectId, refId, "comment", "发表了评论");

        String refName = kindLabel(kind);
        for (Long mid : mentions) {
            notificationService.push(mid, "mention",
                    actor.name() + " 在" + refName + " " + refId + " 的评论中 @了你",
                    "mention", canon(kind), refId);
        }
        return Map.of("ok", true, "id", "c" + comment.getId());
    }

    @Transactional
    public Map<String, Object> deleteComment(String rawId) {
        Long id = parseId(rawId, "c");
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw BizException.notFound("评论不存在");
        }
        requireModule(comment.getKind(), "编辑");
        requireSelfOrManager(comment.getProjectId(), comment.getMemberId());
        commentMapper.deleteById(id);
        return Map.of("ok", true);
    }

    // ===== 附件 =====

    @Transactional
    public Map<String, Object> addAttachment(String kind, String refId, Map<String, Object> meta) {
        Long projectId = projectContext.currentProjectId();
        requireModule(kind, "编辑");
        ensure(kind, refId, projectId);

        String name = meta.get("name") == null ? "附件" : String.valueOf(meta.get("name"));
        Object sizeVal = meta.get("size");
        long size = sizeVal instanceof Number n ? n.longValue() : 0L;
        Object kindVal = meta.get("kind");
        String fileType = kindVal == null ? "file" : String.valueOf(kindVal);
        String url = meta.get("url") == null ? null : String.valueOf(meta.get("url"));
        String storageKey = meta.get("key") == null || String.valueOf(meta.get("key")).isBlank()
                ? null : String.valueOf(meta.get("key"));

        Attachment a = new Attachment();
        a.setProjectId(projectId);
        a.setEntityKind(canon(kind));
        a.setRefId(refId);
        a.setName(name);
        a.setSize(size);
        a.setFileType(fileType);
        a.setUrl(url);
        a.setStorageKey(storageKey);
        a.setMemberId(AuthContext.require().id());
        a.setCreatedAt(LocalDateTime.now());
        attachmentMapper.insert(a);

        push(kind, projectId, refId, "attach", "上传附件 " + name);
        return Map.of("ok", true, "id", "a" + a.getId());
    }

    /** 删除附件：同步删除 RustFS 对象（失败仅记日志不阻断） */
    @Transactional
    public Map<String, Object> removeAttachment(String kind, String refId, String rawAid) {
        Long projectId = projectContext.currentProjectId();
        requireModule(kind, "编辑");
        ensure(kind, refId, projectId);

        Long aid = parseId(rawAid, "a");
        Attachment a = attachmentMapper.selectOne(new LambdaQueryWrapper<Attachment>()
                .eq(Attachment::getId, aid)
                .eq(Attachment::getProjectId, projectId)
                .eq(Attachment::getEntityKind, canon(kind))
                .eq(Attachment::getRefId, refId));
        if (a == null) {
            throw BizException.notFound("附件不存在");
        }
        requireSelfOrManager(a.getProjectId(), a.getMemberId());
        attachmentMapper.deleteById(aid);
        if (a.getStorageKey() != null && !a.getStorageKey().isBlank()) {
            storageService.delete(a.getStorageKey());
        }
        push(kind, projectId, refId, "attach", "删除附件 " + a.getName());
        return Map.of("ok", true);
    }

    // ===== 关注 =====

    /** 切换关注：返回新数组（前端依赖引用变更刷新视图） */
    @Transactional
    public Map<String, Object> toggleWatch(String kind, String refId) {
        Long projectId = projectContext.currentProjectId();
        requireModule(kind, "编辑");
        ensure(kind, refId, projectId);

        Long me = AuthContext.require().id();
        String k = canon(kind);
        Long existing = watcherMapper.selectOne(new LambdaQueryWrapper<Watcher>()
                        .eq(Watcher::getProjectId, projectId)
                        .eq(Watcher::getKind, k)
                        .eq(Watcher::getRefId, refId)
                        .eq(Watcher::getMemberId, me)) == null ? null : me;
        boolean added = existing == null;
        if (added) {
            Watcher w = new Watcher();
            w.setProjectId(projectId);
            w.setKind(k);
            w.setRefId(refId);
            w.setMemberId(me);
            watcherMapper.insert(w);
        } else {
            watcherMapper.delete(new LambdaQueryWrapper<Watcher>()
                    .eq(Watcher::getProjectId, projectId)
                    .eq(Watcher::getKind, k)
                    .eq(Watcher::getRefId, refId)
                    .eq(Watcher::getMemberId, me));
        }
        push(kind, projectId, refId, "watch", added ? "开始关注" : "取消关注");

        List<Long> watchers = watcherMapper.selectList(new LambdaQueryWrapper<Watcher>()
                        .eq(Watcher::getProjectId, projectId)
                        .eq(Watcher::getKind, k)
                        .eq(Watcher::getRefId, refId))
                .stream().map(Watcher::getMemberId).sorted().toList();
        Map<String, Object> result = new HashMap<>();
        result.put("ok", true);
        result.put("watchers", watchers);
        result.put("watching", added);
        return result;
    }

    /** 'c9' / 'a12' → 主键（去掉展示前缀） */
    public static Long parseId(String raw, String prefix) {
        if (raw == null || raw.isBlank()) {
            throw BizException.bad("无效 id");
        }
        String s = raw.startsWith(prefix) ? raw.substring(prefix.length()) : raw;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw BizException.bad("无效 id：" + raw);
        }
    }

    /** JSON 字符串数组 → List<Long>（非数字项忽略） */
    private static List<Long> longList(String json) {
        List<Long> out = new ArrayList<>();
        for (String s : Jsonz.stringList(json)) {
            try {
                out.add(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // 非数字成员 id（如旧数据 m5）忽略
            }
        }
        return out;
    }
}
