package dev.clearflow.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 结构化状态流转事件：周期、停留时长、重开率的统计口径（与展示用 activity 文本解耦） */
@Data
@TableName("status_event")
public class StatusEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** task / defect / requirement */
    private String kind;

    private String entityId;

    private String fromStatus;

    private String toStatus;

    private Long byMember;

    private LocalDateTime at;
}
