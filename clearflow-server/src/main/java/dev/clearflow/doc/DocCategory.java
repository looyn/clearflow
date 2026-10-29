package dev.clearflow.doc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 文档分类字典（系统级，与 mock docCategories 一致） */
@Data
@TableName("doc_category")
public class DocCategory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
}
