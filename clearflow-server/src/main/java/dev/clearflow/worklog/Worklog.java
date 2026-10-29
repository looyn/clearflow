package dev.clearflow.worklog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 工时记录（id=W-133；who→member_id、date→work_date、task→ref_text 为前端字段映射） */
@Data
@TableName("worklog")
public class Worklog {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private Long projectId;

    @TableField("member_id")
    private Long memberId;

    @TableField("work_date")
    private String workDate;

    @TableField("ref_text")
    private String refText;

    /** task / defect / other；NULL 为旧数据，读取时按 ref_text 前缀推断 */
    @TableField("ref_kind")
    private String refKind;

    /** 关联实体编号（T-xxx / B-xxx），其他类型为空 */
    @TableField("ref_id")
    private String refId;

    private Double hours;

    private String note;
}
