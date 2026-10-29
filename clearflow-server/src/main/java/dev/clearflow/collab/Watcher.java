package dev.clearflow.collab;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 关注者（联合唯一：project + kind + ref + member） */
@Data
@TableName("watcher")
public class Watcher {

    private Long projectId;

    private String kind;

    private String refId;

    private Long memberId;
}
