package dev.clearflow.doc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 文档（Markdown 原文存 content；分类取自 doc_category 字典） */
@Data
@TableName("doc")
public class Doc {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long projectId;
    private String title;
    private String category;
    private String content;
    private Long owner;
    private String created;
    private String updated;

    public record View(String id, String title, String category, String content,
                       Long owner, String created, String updated) {
        public static View of(Doc d) {
            return new View(d.getId(), d.getTitle(), d.getCategory(), d.getContent(),
                    d.getOwner(), d.getCreated(), d.getUpdated());
        }
    }
}
