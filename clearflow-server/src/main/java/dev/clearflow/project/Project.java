package dev.clearflow.project;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目 */
@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目代号，如 EMP */
    private String code;

    private String name;

    private String description;

    /** 负责人 member.id */
    private Long owner;

    /** planned / active / archived */
    private String state;

    private String startDate;

    private String endDate;

    private String color;

    private Integer progress;
}
