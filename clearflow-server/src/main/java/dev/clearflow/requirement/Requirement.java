package dev.clearflow.requirement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.Times;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 需求（状态为中文枚举直存） */
@Data
@TableName("requirement")
public class Requirement {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long projectId;

    /** 业务模块 project_module.id（null = 未归类） */
    private Long moduleId;

    private String title;

    private String type;

    private String prio;

    /** 待评审 / 已排期 / 研发中 / 已发布 */
    private String status;

    private Long owner;

    private String source;

    private String iter;

    private Integer estimate;

    private String targetRelease;

    private String created;

    /** 首次到达终态的时间；离开终态（重开）时清空 */
    private LocalDateTime completedAt;

    @TableField("description")
    private String description;

    // ===== 对外视图 =====

    public record AcceptanceView(String id, String title, Boolean done) {
    }

    public record View(String id, String title, String type, String prio, String status, Long owner,
                       Long moduleId, String moduleName, String source, String iter, Integer estimate,
                       String release, String created, String completedAt,
                       String desc, List<AcceptanceView> acceptance, List<Long> watchers,
                       List<CollabService.AttView> attachments, List<CollabService.ActView> activity) {

        public static View of(Requirement r, String moduleName, List<AcceptanceView> acceptance, List<Long> watchers,
                              List<CollabService.AttView> attachments, List<CollabService.ActView> activity) {
            return new View(r.getId(), r.getTitle(), r.getType(), r.getPrio(), r.getStatus(),
                    r.getOwner(), r.getModuleId(), moduleName, r.getSource(), r.getIter(), r.getEstimate(),
                    r.getTargetRelease() == null ? "—" : r.getTargetRelease(),
                    r.getCreated(), Times.format(r.getCompletedAt()),
                    r.getDescription() == null ? "" : r.getDescription(),
                    acceptance, watchers, attachments, activity);
        }
    }
}
