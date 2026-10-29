package dev.clearflow.iteration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 迭代里程碑 */
@Data
@TableName("milestone")
public class Milestone {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String iterationCode;

    private String msDate;

    private String name;

    /** done / now / 空 */
    private String state;
}
