package dev.clearflow.module;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务模块（项目内的产品/技术域划分，如支付网关、收银台）。
 * 与 permission_grant.module_key（功能菜单授权）无关：这里是统计维度与归类维度。
 */
@Data
@TableName("project_module")
public class ProjectModule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String name;

    private Long owner;

    private String description;

    private Integer sort;

    private LocalDateTime createdAt;

    /** 设置页展示用：模块下挂载的实体数量（删除前用于提示） */
    public record View(Long id, String name, Long owner, String ownerName, String description, Integer sort,
                       long requirementCount, long taskCount, long defectCount) {
    }
}
