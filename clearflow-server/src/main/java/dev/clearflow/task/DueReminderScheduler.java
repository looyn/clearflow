package dev.clearflow.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.clearflow.notify.NotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 到期提醒：每天 09:00 给今日到期任务的负责人推 kind=due 通知
 * （due 为展示用 VARCHAR：'2026-10-10 00:00:00' / '今日' / '待排期'，仅可解析日期与今日参与判定）
 */
@Component
public class DueReminderScheduler {

    private final TaskMapper taskMapper;
    private final NotificationService notificationService;

    public DueReminderScheduler(TaskMapper taskMapper, NotificationService notificationService) {
        this.taskMapper = taskMapper;
        this.notificationService = notificationService;
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void remind() {
        String today = LocalDate.now().toString();
        String mmdd = today.substring(5).replace('-', '.'); // 10.10
        List<Task> dueTasks = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                        .ne(Task::getStatus, "done")
                        .isNotNull(Task::getAssignee)
                        .and(w -> w.likeRight(Task::getDue, today).or().eq(Task::getDue, "今日")));
        for (Task t : dueTasks) {
            notificationService.push(t.getAssignee(), "due",
                    "任务 " + t.getId() + "「" + t.getTitle() + "」已到期（" + mmdd + "）",
                    "daily", "task", t.getId());
        }
    }
}
