package dev.clearflow.collab;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 附件（entity_kind 多态挂载；file_type 为 image/file） */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String entityKind;

    private String refId;

    private String name;

    private Long size;

    private String fileType;

    private String url;

    /** RustFS 对象键，读取时预签名 */
    private String storageKey;

    private Long memberId;

    private LocalDateTime createdAt;
}
