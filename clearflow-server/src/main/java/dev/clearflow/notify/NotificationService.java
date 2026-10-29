package dev.clearflow.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.PageResult;
import dev.clearflow.common.Times;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知：生成受「通知设置」个人开关控制，不给操作者本人推送；
 * 查询/已读/删除全部限定 member_id = 当前登录人，互不干扰。
 */
@Service
public class NotificationService {

    private final NotificationMapper mapper;
    private final SettingsService settingsService;

    public NotificationService(NotificationMapper mapper, SettingsService settingsService) {
        this.mapper = mapper;
        this.settingsService = settingsService;
    }

    /**
     * 推送一条通知（开关关闭或收件人是自己时静默跳过）
     *
     * @param switchKey 通知设置里的开关 key（mention / assign / release / defect / daily）
     */
    @Transactional
    public void push(Long memberId, String kind, String text, String switchKey,
                     String refKind, String refId) {
        if (memberId == null || !switchOn(memberId, switchKey)) {
            return;
        }
        MemberPrincipal principal = currentPrincipal();
        if (principal != null && principal.id().equals(memberId)) {
            return; // 不通知自己
        }
        Notification n = new Notification();
        n.setMemberId(memberId);
        n.setIsRead(false);
        n.setKind(kind);
        n.setTitle("mention".equals(kind) ? "有人在评论中提到了你" : null);
        n.setText(text);
        n.setRefKind(refKind);
        n.setRefId(refId);
        n.setCreatedAt(java.time.LocalDateTime.now());
        mapper.insert(n);
    }

    /** 按收件人自己的开关判断（member_setting.notify；未配置时用默认开关） */
    private boolean switchOn(Long memberId, String switchKey) {
        if (switchKey == null || switchKey.isBlank()) {
            return true;
        }
        JsonNode notify = settingsService.notifyFor(memberId);
        if (notify == null || !notify.isArray()) {
            return true;
        }
        for (JsonNode item : notify) {
            if (switchKey.equals(item.path("key").asText())) {
                return item.path("on").asBoolean(true);
            }
        }
        return true;
    }

    private static MemberPrincipal currentPrincipal() {
        try {
            return AuthContext.require();
        } catch (RuntimeException e) {
            return null; // 定时任务等无登录上下文场景
        }
    }

    // ===== 查询 =====

    /** 我的通知（分页，新在前；createdAt 供前端做相对时间与按天分组） */
    public PageResult<Map<String, Object>> page(int page, int pageSize) {
        Long me = AuthContext.require().id();
        Page<Notification> result = mapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<Notification>()
                        .eq(Notification::getMemberId, me)
                        .orderByDesc(Notification::getCreatedAt)
                        .orderByDesc(Notification::getId));
        return PageResult.of(result.getRecords().stream().map(NotificationService::toRow).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /** 未读数（铃铛角标，无需拉全量） */
    public long unreadCount() {
        return mapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getMemberId, AuthContext.require().id())
                .eq(Notification::getIsRead, false));
    }

    private static Map<String, Object> toRow(Notification n) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", n.getId());
        row.put("read", Boolean.TRUE.equals(n.getIsRead()));
        row.put("kind", n.getKind());
        row.put("title", n.getTitle() == null ? "" : n.getTitle());
        row.put("text", n.getText());
        row.put("refKind", n.getRefKind());
        row.put("refId", n.getRefId());
        row.put("at", Times.format(n.getCreatedAt()));
        // ISO 本地时间（无时区后缀），JS new Date() 按本地时区解析
        row.put("createdAt", n.getCreatedAt() == null ? "" : n.getCreatedAt().toString());
        return row;
    }

    // ===== 已读 / 删除（均限定本人，避免越权操作他人通知）=====

    /** 单条置为已读/未读（read=false 即「标为未读」） */
    @Transactional
    public Map<String, Object> markRead(Long id, boolean read) {
        mapper.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getMemberId, AuthContext.require().id())
                .set(Notification::getIsRead, read));
        return Map.of("ok", true);
    }

    /** 全部已读 */
    @Transactional
    public Map<String, Object> markAllRead() {
        mapper.update(new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getMemberId, AuthContext.require().id())
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true));
        return Map.of("ok", true);
    }

    /** 删除单条 */
    @Transactional
    public Map<String, Object> delete(Long id) {
        mapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getId, id)
                .eq(Notification::getMemberId, AuthContext.require().id()));
        return Map.of("ok", true);
    }

    /** 清空已读（治理堆积；未读的保留） */
    @Transactional
    public Map<String, Object> clearRead() {
        int deleted = mapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getMemberId, AuthContext.require().id())
                .eq(Notification::getIsRead, true));
        return Map.of("ok", true, "deleted", deleted);
    }
}
