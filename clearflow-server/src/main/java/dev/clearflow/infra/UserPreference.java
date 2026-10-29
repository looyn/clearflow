package dev.clearflow.infra;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 用户偏好：当前项目 */
@Data
@TableName("user_preference")
public class UserPreference {

    @TableId(type = IdType.INPUT)
    private Long memberId;

    private Long currentProjectId;
}
