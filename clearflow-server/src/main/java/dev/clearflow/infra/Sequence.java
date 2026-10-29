package dev.clearflow.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 业务编号序列（task/requirement/defect/release/doc/worklog 各一行） */
@Data
@TableName("sequence")
public class Sequence {

    @TableId(value = "biz_type", type = IdType.INPUT)
    private String bizType;

    private Long nextVal;
}
