package dev.clearflow.notify;

import dev.clearflow.common.PageResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 站内通知（全部限定当前登录人） */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** 我的通知（分页：?page=1&pageSize=20） */
    @GetMapping
    public PageResult<Map<String, Object>> list(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int pageSize) {
        return notificationService.page(Math.max(1, page), Math.min(100, Math.max(1, pageSize)));
    }

    /** 未读数（铃铛角标） */
    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", notificationService.unreadCount());
    }

    /** 单条置为已读（或标为未读，read=false） */
    @PutMapping("/{id}/read")
    public Map<String, Object> markRead(@PathVariable Long id,
                                        @RequestParam(defaultValue = "true") boolean read) {
        return notificationService.markRead(id, read);
    }

    /** 全部已读 */
    @PutMapping("/read-all")
    public Map<String, Object> markAllRead() {
        return notificationService.markAllRead();
    }

    /** 删除单条 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return notificationService.delete(id);
    }

    /** 清空已读 */
    @DeleteMapping("/read")
    public Map<String, Object> clearRead() {
        return notificationService.clearRead();
    }
}
