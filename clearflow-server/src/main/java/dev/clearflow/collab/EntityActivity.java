package dev.clearflow.collab;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 实体动态（后端在覆盖旧值前生成，审计可信） */
@Data
@TableName("activity")
public class EntityActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String kind;

    private String refId;

    private Long memberId;

    /** create/status/field/comment/attach/subtask/verify/watch */
    private String type;

    private String text;

    private LocalDateTime createdAt;
}
