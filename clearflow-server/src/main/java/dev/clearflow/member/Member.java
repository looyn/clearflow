package dev.clearflow.member;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 系统成员池 */
@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 登录账号（可与邮箱不同，如 admin） */
    private String login;

    private String email;

    private String passwordHash;

    /** 职位（对应前端 JSON 的 role 字段） */
    @TableField("job_title")
    private String role;

    /** 角色：开发 / 只读（前端 JSON 的 perm 字段；项目负责人由 project.owner 承担） */
    private String perm;

    private String color;

    private String joined;

    private Integer capacity;
}
