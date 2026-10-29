package dev.clearflow.perm;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 项目内成员 × 模块授权（覆盖角色默认）：
 * 主键 (project_id, member_id, module_key)——同一成员在不同项目可以有不同的模块等级。
 */
@Data
@TableName("permission_grant")
public class PermissionGrant {

    /** 所属项目（授权只在本项目内生效） */
    private Long projectId;

    private Long memberId;

    private String moduleKey;

    private String level;
}
