package dev.clearflow.requirement;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 需求验收标准条目 */
@Data
@TableName("acceptance_item")
public class AcceptanceItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String reqId;

    private String title;

    private Boolean done;
}
