package dev.clearflow.settings;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目级设置行（workflow / options），data 为 JSON 原文 */
@Data
@TableName("project_setting")
public class ProjectSettingRow {

    private Long projectId;

    private String section;

    private String data;
}
