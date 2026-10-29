package dev.clearflow.iteration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 迭代日快照（燃尽图数据源，每天每迭代最多一条） */
@Data
@TableName("iteration_snapshot")
public class IterationSnapshot {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long projectId;

    @TableField("iteration_code")
    private String iterationCode;

    @TableField("snap_date")
    private String snapDate;

    /** 剩余点数（迭代总点数 - 已完成点数） */
    private Integer remaining;

    /** 已完成点数 */
    private Integer donePoints;
}
