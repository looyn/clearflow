package dev.clearflow.defect;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.Times;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 缺陷 */
@Data
@TableName("defect")
public class Defect {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long projectId;

    /** 业务模块 project_module.id（未填时从关联需求继承） */
    private Long moduleId;

    private String title;

    private String env;

    private String prio;

    private String severity;

    /** open / fixing / fixed / verified / closed */
    private String status;

    /** 看板同一状态列内的手动排序值（升序，步长 1024） */
    private Long sort;

    /** 当前处理人 */
    private Long owner;

    /** 报告人展示名（外部反馈人没有成员账号，如「客服-王芳」） */
    private String reporter;

    /** 内部报告人 member.id；外部反馈人为 null */
    private Long reporterId;

    /** 引入缺陷的成员：只用于复盘归因，不作为个人排名/考核指标 */
    private Long introducedBy;

    /** 功能 / 性能 / 兼容性 / 安全 / 数据 / 体验 */
    private String defectType;

    /** 根因分类：需求遗漏 / 设计缺陷 / 编码错误 / 测试遗漏 / 环境问题 */
    private String rootCause;

    /** 修复版本 */
    private String fixedIn;

    private String date;

    private String iter;

    @TableField("found_in")
    private String foundIn;

    private String deadline;

    private String reqId;

    @TableField("description")
    private String description;

    /** 已投入工时（小时），由工时登记同步增减 */
    private Double spent;

    /** 到达「已修复」档的时间；重开时清空 */
    private LocalDateTime resolvedAt;

    /** 到达终态的时间；重开时清空 */
    private LocalDateTime closedAt;

    /** 终态 → 非终态的次数 */
    private Integer reopenCount;

    // ===== 对外视图 =====

    public record VerifyView(String id, Long by, String time, String result, String note) {
    }

    public record RelateView(String id, String type, String target) {
    }

    public record View(String id, String title, String env, String prio, String severity,
                       String status, Long owner, String reporter, Long reporterId, Long introducedBy,
                       Long moduleId, String moduleName, String defectType, String rootCause, String fixedIn,
                       String date, String iter, String foundIn, String deadline, String reqId, String desc,
                       String resolvedAt, String closedAt, Integer reopenCount,
                       List<VerifyView> verifyLog, List<RelateView> relates, List<Long> watchers,
                       List<CollabService.AttView> attachments, List<CollabService.ActView> activity,
                       Long sort, Double spent) {

        public static View of(Defect d, String moduleName, List<VerifyView> verifyLog, List<RelateView> relates,
                              List<Long> watchers, List<CollabService.AttView> attachments,
                              List<CollabService.ActView> activity) {
            return new View(d.getId(), d.getTitle(), d.getEnv(), d.getPrio(), d.getSeverity(),
                    d.getStatus(), d.getOwner(), d.getReporter(), d.getReporterId(), d.getIntroducedBy(),
                    d.getModuleId(), moduleName,
                    d.getDefectType() == null ? "功能" : d.getDefectType(),
                    d.getRootCause() == null ? "" : d.getRootCause(),
                    d.getFixedIn() == null ? "" : d.getFixedIn(),
                    d.getDate(), d.getIter(),
                    d.getFoundIn() == null ? "—" : d.getFoundIn(),
                    d.getDeadline() == null ? "—" : d.getDeadline(),
                    d.getReqId(), d.getDescription() == null ? "" : d.getDescription(),
                    Times.format(d.getResolvedAt()), Times.format(d.getClosedAt()),
                    d.getReopenCount() == null ? 0 : d.getReopenCount(),
                    verifyLog, relates, watchers, attachments, activity,
                    d.getSort() == null ? 0L : d.getSort(),
                    d.getSpent() == null ? 0.0 : d.getSpent());
        }
    }
}
