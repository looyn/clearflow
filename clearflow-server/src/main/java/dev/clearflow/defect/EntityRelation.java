package dev.clearflow.defect;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 缺陷关联关系（blocks / duplicate / relates 等） */
@Data
@TableName("entity_relation")
public class EntityRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String defectId;

    private String type;

    private String target;
}
