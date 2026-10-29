package dev.clearflow.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import dev.clearflow.collab.CollabService;
import dev.clearflow.common.Jsonz;
import lombok.Data;

import java.util.List;

/** 发版计划（release 为 MySQL 保留字，表名反引号） */
@Data
@TableName("`release`")
public class Release {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long projectId;

    private String version;

    private String name;

    /** planning / testing / ready / released / rolled */
    private String status;

    @TableField("rel_date")
    private String date;

    private Long owner;

    private String channel;

    private String risk;

    /** 富文本 HTML 发版说明 */
    private String notes;

    @TableField("req_ids")
    private String reqIds;

    @TableField("defect_ids")
    private String defectIds;

    private String created;

    private String updated;

    // ===== 对外视图 =====

    public record View(String id, String version, String name, String status, String date,
                       Long owner, String channel, String risk, String notes,
                       List<String> reqIds, List<String> defectIds, String created, String updated,
                       List<Long> watchers, List<CollabService.AttView> attachments,
                       List<CollabService.ActView> activity) {

        public static View of(Release r, List<Long> watchers,
                              List<CollabService.AttView> attachments,
                              List<CollabService.ActView> activity) {
            return new View(r.getId(), r.getVersion(), r.getName(), r.getStatus(), r.getDate(),
                    r.getOwner(), r.getChannel(), r.getRisk(),
                    r.getNotes() == null ? "" : r.getNotes(),
                    Jsonz.stringList(r.getReqIds()), Jsonz.stringList(r.getDefectIds()),
                    r.getCreated(), r.getUpdated(), watchers, attachments, activity);
        }
    }
}
