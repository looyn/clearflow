package dev.clearflow.settings;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 成员级设置行（notify 等个人偏好），data 为 JSON 原文 */
@Data
@TableName("member_setting")
public class MemberSettingRow {

    private Long memberId;

    private String section;

    private String data;
}
