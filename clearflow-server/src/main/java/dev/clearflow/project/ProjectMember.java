package dev.clearflow.project;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 项目成员（引用系统成员池） */
@Data
@TableName("project_member")
public class ProjectMember {

    private Long projectId;

    private Long memberId;
}
