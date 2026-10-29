package dev.clearflow.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.Jsonz;
import dev.clearflow.common.Times;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 任务（看板卡片） */
@Data
@TableName("task")
public class Task {

    /** 展示码主键：T-316 */
    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long projectId;

    /** 业务模块 project_module.id（null = 未归类；未填时从关联需求继承） */
    private Long moduleId;

    private String title;

    /** 前端类型：fe / be / qa / de */
    private String type;

    /** 优先级：p0 / p1 / p2 */
    private String prio;

    private Long assignee;

    /** 自由文本：待排期 / 今日 / 日期 */
    private String due;

    private Boolean hot;

    /** todo / doing / review / done */
    private String status;

    /** 看板同一状态列内的手动排序值（升序，步长 1024） */
    private Long sort;

    private String iter;

    private Integer estimate;

    private Double spent;

    /** 关联需求编号 */
    private String reqId;

    /** JSON 字符串数组 */
    private String labels;

    private Long creator;

    private String created;

    private String updated;

    /** 到达终态的时间；离开终态时清空 */
    private LocalDateTime completedAt;

    @TableField("description")
    private String description;

    /** 前置任务编号列表（predIds，JSON 字符串数组，可为空） */
    @TableField("pred_ids")
    private String predIds;

    // ===== 对外视图（与前端 mock 任务字段一致） =====

    public record View(String id, String title, String type, String prio, Long assignee, String due,
                       Boolean hot, String status, Long moduleId, String moduleName,
                       String iter, Integer estimate, Double spent,
                       String reqId, List<String> labels, List<String> predIds, Long creator,
                       String created, String updated, String completedAt, String desc,
                       List<Subtask.View> subtasks, List<Long> watchers,
                       List<CollabService.AttView> attachments, List<CollabService.ActView> activity) {

        public static View of(Task t, String moduleName, List<Subtask.View> subtasks, List<Long> watchers,
                              List<CollabService.AttView> attachments, List<CollabService.ActView> activity) {
            return new View(t.getId(), t.getTitle(), t.getType(), t.getPrio(), t.getAssignee(), t.getDue(),
                    Boolean.TRUE.equals(t.getHot()), t.getStatus(), t.getModuleId(), moduleName,
                    t.getIter(), t.getEstimate(),
                    t.getSpent() == null ? 0 : t.getSpent(), t.getReqId(),
                    Jsonz.stringList(t.getLabels()), Jsonz.stringList(t.getPredIds()),
                    t.getCreator(), t.getCreated(), t.getUpdated(), Times.format(t.getCompletedAt()),
                    t.getDescription() == null ? "" : t.getDescription(),
                    subtasks, watchers, attachments, activity);
        }
    }
}
