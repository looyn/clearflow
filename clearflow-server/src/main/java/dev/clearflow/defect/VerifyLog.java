package dev.clearflow.defect;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 缺陷验证日志 */
@Data
@TableName("verify_log")
public class VerifyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String defectId;

    private Long byId;

    private String time;

    private String result;

    private String note;
}
