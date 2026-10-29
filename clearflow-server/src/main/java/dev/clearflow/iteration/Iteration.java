package dev.clearflow.iteration;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.List;

/** 迭代 */
@Data
@TableName("iteration")
public class Iteration {

    @TableId(value = "code", type = IdType.INPUT)
    private String code;

    private Long projectId;

    private String name;

    @TableField("iter_range")
    private String iterRange;

    private String days;

    /** planned / active / released */
    private String state;

    private Integer pct;

    private Integer done;

    private Integer total;

    private Long owner;

    private String goal;

    /** 纯文本或 {summary,scores,actions} JSON */
    private String retro;

    private static final java.util.Map<String, String> STATE_LABEL = java.util.Map.of(
            "planned", "已规划", "active", "进行中", "released", "已发布");

    public record MilestoneView(String d, String n, String state) {
    }

    /**
     * 迭代视图。retro 有两种形态：结构化复盘（对象）与历史纯文本复盘（字符串），
     * 列表行不下发复盘（null），只有详情接口带。
     */
    public record View(String code, String name, String range, String days, String state,
                       String stateLabel, Integer pct, Integer done, Integer total, Long owner,
                       String goal, List<MilestoneView> milestones, Object retro) {

        public static View of(Iteration it, List<MilestoneView> milestones) {
            return of(it, milestones, it.getRetro());
        }

        public static View of(Iteration it, List<MilestoneView> milestones, Object retro) {
            return new View(it.getCode(), it.getName(), it.getIterRange(), it.getDays(), it.getState(),
                    STATE_LABEL.getOrDefault(it.getState(), it.getState()),
                    it.getPct() == null ? 0 : it.getPct(),
                    it.getDone() == null ? 0 : it.getDone(),
                    it.getTotal() == null ? 0 : it.getTotal(),
                    it.getOwner(), it.getGoal() == null ? "" : it.getGoal(),
                    milestones, retro);
        }
    }
}
