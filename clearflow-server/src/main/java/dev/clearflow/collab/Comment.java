package dev.clearflow.collab;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 评论（多态：kind + ref_id 挂载到任务/需求/缺陷/发版计划） */
@Data
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    /** task / requirement / defect / release / doc（req 已归一化为 requirement） */
    private String kind;

    private String refId;

    private Long memberId;

    private String text;

    /** 被 @ 的成员 id 数组（JSON） */
    private String mentions;

    private LocalDateTime createdAt;
}
