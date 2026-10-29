package dev.clearflow.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 概览页全局动态行（html 与 mock logActivity 格式一致） */
@Data
@TableName("overview_activity")
public class OverviewActivity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String html;

    private LocalDateTime createdAt;
}
