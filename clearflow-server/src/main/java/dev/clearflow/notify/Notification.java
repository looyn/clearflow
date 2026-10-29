package dev.clearflow.notify;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 站内通知（铃铛面板） */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    /** 前端字段 read */
    private Boolean isRead;

    /** mention / status / due / defect / ci */
    private String kind;

    private String title;

    private String text;

    private String refKind;

    private String refId;

    private java.time.LocalDateTime createdAt;
}
