package dev.clearflow.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 任务子任务 */
@Data
@TableName("subtask")
public class Subtask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long projectId;

    private String taskId;

    private String title;

    private Boolean done;

    public record View(String id, String title, Boolean done) {
        public static View of(Subtask s) {
            return new View("s" + s.getId(), s.getTitle(), Boolean.TRUE.equals(s.getDone()));
        }
    }
}
