package dev.clearflow.settings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.clearflow.common.BizException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置读写，分三类：
 * 1) 项目级 workflow/options —— project_setting 表（每项目一份）；
 * 2) 成员级 notify —— member_setting 表（每人一份）；
 * 保存语义与前端 mock 一致：数组补丁整段替换、notify 对象补丁按 key 更新 on、对象补丁浅合并。
 * （Spring Boot 4 使用 Jackson 3：tools.jackson 包）
 */
@Service
public class SettingsService {

    private final ProjectSettingMapper projectMapper;
    private final MemberSettingMapper memberMapper;
    private final JsonMapper objectMapper = JsonMapper.builder().build();

    public SettingsService(ProjectSettingMapper projectMapper, MemberSettingMapper memberMapper) {
        this.projectMapper = projectMapper;
        this.memberMapper = memberMapper;
    }

    // ===== 项目级设置（workflow / options，project_setting 表）=====

    /** 项目级分区读取；行不存在时回退默认配置（新建项目可用） */
    public Map<String, JsonNode> getForProject(Long projectId) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (String section : List.of("workflow", "options")) {
            ProjectSettingRow row = projectMapper.selectOne(new LambdaQueryWrapper<ProjectSettingRow>()
                    .eq(ProjectSettingRow::getProjectId, projectId)
                    .eq(ProjectSettingRow::getSection, section));
            if (row != null) {
                result.put(section, objectMapper.readTree(row.getData()));
            } else {
                // 该项目尚未写入该分区（如新建项目）：回退默认，避免看板/表单空配置
                result.put(section, defaultSection(section));
            }
        }
        return result;
    }

    /** 项目级分区的默认配置（与种子数据一致；新建项目在未保存前读取到的内容） */
    private JsonNode defaultSection(String section) {
        return switch (section) {
            case "workflow" -> objectMapper.readTree("""
                    {"columns":[
                      {"key":"todo","name":"待处理","color":"#8F959E"},
                      {"key":"doing","name":"进行中","color":"#3370FF"},
                      {"key":"review","name":"待验收","color":"#7A5AF8"},
                      {"key":"done","name":"已完成","color":"#22C55E"}
                    ],
                     "defectColumns":[
                      {"key":"open","name":"待处理","color":"#F54A45"},
                      {"key":"fixing","name":"修复中","color":"#FF7D00"},
                      {"key":"fixed","name":"已修复","color":"#3370FF"},
                      {"key":"verified","name":"已验证","color":"#2EA86E"},
                      {"key":"closed","name":"已关闭","color":"#8F959E"}
                    ],
                     "reqColumns":[
                      {"key":"待评审","name":"待评审","color":"#8F959E"},
                      {"key":"已排期","name":"已排期","color":"#3370FF"},
                      {"key":"研发中","name":"研发中","color":"#FF7D00"},
                      {"key":"已发布","name":"已发布","color":"#22C55E"}
                    ],
                     "relColumns":[
                      {"key":"planning","name":"筹备中","color":"#8F959E"},
                      {"key":"testing","name":"测试中","color":"#3370FF"},
                      {"key":"ready","name":"待发布","color":"#FF7D00"},
                      {"key":"released","name":"已发布","color":"#22C55E"}
                    ],
                     "iterColumns":[
                      {"key":"planned","name":"已规划","color":"#8F959E"},
                      {"key":"active","name":"进行中","color":"#3370FF"},
                      {"key":"released","name":"已发布","color":"#22C55E"}
                    ]}
                    """);
            case "options" -> objectMapper.readTree("""
                    {"priorities":[
                       {"key":"p0","name":"P0 紧急","color":"#F54A45"},
                       {"key":"p1","name":"P1 高","color":"#FF7D00"},
                       {"key":"p2","name":"P2 普通","color":"#3370FF"}],
                     "severities":[
                       {"key":"致命","name":"致命","color":"#F54A45"},
                       {"key":"严重","name":"严重","color":"#FF7D00"},
                       {"key":"一般","name":"一般","color":"#3370FF"},
                       {"key":"轻微","name":"轻微","color":"#8F959E"}],
                     "taskTypes":[
                       {"key":"fe","name":"前端","color":"#3370FF"},
                       {"key":"be","name":"后端","color":"#22C55E"},
                       {"key":"qa","name":"测试","color":"#7A5AF8"},
                       {"key":"de","name":"设计","color":"#FF7D00"}],
                     "labels":["核心链路","支付","性能","对账","体验","文档"],
                     "envs":["生产","预发","测试"]}
                    """);
            default -> objectMapper.readTree("{}");
        };
    }

    /**
     * 项目设置里的优先级选项（key → name，保持配置顺序），未配置时回退默认三级。
     * 统计、动态文案等需要把 prio key 显示给人看的地方统一走这里，自定义优先级（如 k4）才会生效。
     */
    public Map<String, String> priorityNames(Long projectId) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode options = getForProject(projectId).get("options");
        JsonNode arr = options == null ? null : options.get("priorities");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                JsonNode key = n.get("key");
                if (key == null || key.asText("").isBlank()) {
                    continue;
                }
                JsonNode name = n.get("name");
                out.put(key.asText(""), name == null || name.asText("").isBlank() ? key.asText("").toUpperCase() : name.asText(""));
            }
        }
        if (out.isEmpty()) {
            out.put("p0", "P0 紧急");
            out.put("p1", "P1 高");
            out.put("p2", "P2 普通");
        }
        return out;
    }

    /** 优先级展示名；配置里没有该 key 时回退 key 大写（历史数据仍可读） */
    public String priorityLabel(Long projectId, String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        String label = priorityNames(projectId).get(key);
        return label == null ? key.toUpperCase() : label;
    }

    // ===== 各实体状态流（workflow.<section>：columns / defectColumns / reqColumns / relColumns / iterColumns）=====

    /** key/name 对构造（DEFAULT_FLOWS 用） */
    private static LinkedHashMap<String, String> defs(String... kv) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    /**
     * 各实体默认状态流（key → name，顺序即单向流转顺序，**最后一档为终态**），
     * 与前端 stores/options.js 的 DEFAULT_WORKFLOW 及种子数据一致。
     * 需求的 key 即存量数据里的中文状态，改名只改展示。
     */
    private static final Map<String, Map<String, String>> DEFAULT_FLOWS = Map.of(
            "columns", defs("todo", "待处理", "doing", "进行中", "review", "待验收", "done", "已完成"),
            "defectColumns", defs("open", "待处理", "fixing", "修复中", "fixed", "已修复",
                    "verified", "已验证", "closed", "已关闭"),
            "reqColumns", defs("待评审", "待评审", "已排期", "已排期", "研发中", "研发中", "已发布", "已发布"),
            "relColumns", defs("planning", "筹备中", "testing", "测试中", "ready", "待发布", "released", "已发布"),
            "iterColumns", defs("planned", "已规划", "active", "进行中", "released", "已发布"));

    /** 不进配置的特有状态展示名（发版「已回滚」不参与配置，前后端同为特例） */
    private static final Map<String, Map<String, String>> EXTRA_STATUS_NAMES = Map.of(
            "relColumns", Map.of("rolled", "已回滚"));

    /**
     * 某实体状态流（key → name，保持配置顺序），项目未配置或配置为空时回退默认。
     * 缺陷/任务/需求/发版/迭代的看板列、筛选、流转校验与状态文案统一走这里，自定义状态才会生效。
     */
    public Map<String, String> statusNames(Long projectId, String section) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        JsonNode workflow = getForProject(projectId).get("workflow");
        JsonNode arr = workflow == null ? null : workflow.get(section);
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                JsonNode key = n.get("key");
                if (key == null || key.asText("").isBlank()) {
                    continue;
                }
                JsonNode name = n.get("name");
                out.put(key.asText(""), name == null || name.asText("").isBlank() ? key.asText("") : name.asText(""));
            }
        }
        if (out.isEmpty() && DEFAULT_FLOWS.containsKey(section)) {
            out.putAll(DEFAULT_FLOWS.get(section));
        }
        return out;
    }

    /** 单向流转顺序（配置顺序：只进不退按此序列判断） */
    public List<String> flow(Long projectId, String section) {
        return List.copyOf(statusNames(projectId, section).keySet());
    }

    /** 状态展示名；配置里没有该 key 时回退特有状态名 → key 本身（被删掉的历史状态仍可读） */
    public String statusName(Long projectId, String section, String key) {
        if (key == null || key.isBlank()) {
            return "—";
        }
        String name = statusNames(projectId, section).get(key);
        if (name != null) {
            return name;
        }
        Map<String, String> extra = EXTRA_STATUS_NAMES.get(section);
        return extra != null && extra.containsKey(key) ? extra.get(key) : key;
    }

    /** 终态（流转序列最后一个，如 done / 已发布 / released / closed 被改名或删除后仍能识别） */
    public String terminalStatus(Long projectId, String section) {
        List<String> flow = flow(projectId, section);
        if (!flow.isEmpty()) {
            return flow.get(flow.size() - 1);
        }
        Map<String, String> def = DEFAULT_FLOWS.get(section);
        if (def == null || def.isEmpty()) {
            return "";
        }
        List<String> keys = List.copyOf(def.keySet());
        return keys.get(keys.size() - 1);
    }

    // ===== 缺陷状态（workflow.defectColumns：项目级缺陷工作流，上面通用方法的别名）=====

    /** 项目缺陷状态（key → name，保持配置顺序），未配置时回退默认五态。 */
    public Map<String, String> defectStatusNames(Long projectId) {
        return statusNames(projectId, "defectColumns");
    }

    /** 缺陷单向流转顺序（配置顺序：只进不退按此序列判断） */
    public List<String> defectFlow(Long projectId) {
        return flow(projectId, "defectColumns");
    }

    /** 缺陷状态展示名；配置里没有该 key 时回退 key 本身（被删掉的历史状态仍可读） */
    public String defectStatusName(Long projectId, String key) {
        return statusName(projectId, "defectColumns", key);
    }

    /** 缺陷终态（流转序列的最后一个，如 closed 被改名或删除后仍能识别「已关闭」） */
    public String defectTerminal(Long projectId) {
        return terminalStatus(projectId, "defectColumns");
    }

    /** 项目级分区保存（section 仅允许 workflow/options；复用 merge 语义） */
    public void saveForProject(Long projectId, String section, JsonNode patch) {
        if (!"workflow".equals(section) && !"options".equals(section)) {
            throw BizException.bad("项目级设置仅支持 workflow / options 分区");
        }
        ProjectSettingRow row = projectMapper.selectOne(new LambdaQueryWrapper<ProjectSettingRow>()
                .eq(ProjectSettingRow::getProjectId, projectId)
                .eq(ProjectSettingRow::getSection, section));
        JsonNode current = row == null ? null : objectMapper.readTree(row.getData());
        JsonNode next = merge(section, current, patch);
        if (row == null) {
            row = new ProjectSettingRow();
            row.setProjectId(projectId);
            row.setSection(section);
            row.setData(objectMapper.writeValueAsString(next));
            projectMapper.insert(row);
        } else {
            // 联合主键（project_id+section）无 @TableId，updateById 不可用，按条件更新
            projectMapper.update(
                    new LambdaUpdateWrapper<ProjectSettingRow>()
                            .eq(ProjectSettingRow::getProjectId, projectId)
                            .eq(ProjectSettingRow::getSection, section)
                            .set(ProjectSettingRow::getData, objectMapper.writeValueAsString(next)));
        }
    }

    // ===== 成员级设置（notify 个人通知开关，member_setting 表）=====

    /** 读取某成员的个人通知开关；未配置时回退默认开关列表 */
    public JsonNode notifyFor(Long memberId) {
        MemberSettingRow row = memberMapper.selectOne(new LambdaQueryWrapper<MemberSettingRow>()
                .eq(MemberSettingRow::getMemberId, memberId)
                .eq(MemberSettingRow::getSection, "notify"));
        if (row != null) {
            return objectMapper.readTree(row.getData());
        }
        return defaultNotify();
    }

    /** 保存某成员的个人通知开关分区（section 仅允许 notify） */
    public void saveForMember(Long memberId, String section, JsonNode patch) {
        if (!"notify".equals(section)) {
            throw BizException.bad("成员级设置仅支持 notify 分区");
        }
        MemberSettingRow row = memberMapper.selectOne(new LambdaQueryWrapper<MemberSettingRow>()
                .eq(MemberSettingRow::getMemberId, memberId)
                .eq(MemberSettingRow::getSection, section));
        JsonNode current = row == null ? null : objectMapper.readTree(row.getData());
        JsonNode next = merge(section, current == null ? defaultNotify() : current, patch);
        if (row == null) {
            row = new MemberSettingRow();
            row.setMemberId(memberId);
            row.setSection(section);
            row.setData(objectMapper.writeValueAsString(next));
            memberMapper.insert(row);
        } else {
            memberMapper.update(
                    new LambdaUpdateWrapper<MemberSettingRow>()
                            .eq(MemberSettingRow::getMemberId, memberId)
                            .eq(MemberSettingRow::getSection, section)
                            .set(MemberSettingRow::getData, objectMapper.writeValueAsString(next)));
        }
    }

    /** 默认通知开关（每人首次访问时以此为准；新增开关项也靠这里向后兼容） */
    private JsonNode defaultNotify() {
        return objectMapper.readTree("""
                [
                  {"key":"assign","label":"指派给我的任务","desc":"有人把任务/缺陷指派给我时通知","on":true},
                  {"key":"mention","label":"评论中 @我","desc":"评论提及我时通知","on":true},
                  {"key":"release","label":"发版计划变更","desc":"我负责的发版计划状态变更时通知","on":true},
                  {"key":"defect","label":"P0/P1 缺陷新增","desc":"出现高严重度缺陷时通知","on":true},
                  {"key":"daily","label":"每日站报邮件","desc":"每天 09:00 推送我今日到期的任务","on":false}
                ]
                """);
    }

    private JsonNode merge(String section, JsonNode current, JsonNode patch) {
        if (patch.isArray()) {
            return patch;
        }
        if ("notify".equals(section) && patch.isObject() && current != null && current.isArray()) {
            ArrayNode array = (ArrayNode) current.deepCopy();
            patch.properties().forEach(entry -> {
                for (JsonNode item : array) {
                    if (entry.getKey().equals(item.path("key").asText())) {
                        ((ObjectNode) item).put("on", entry.getValue().asBoolean());
                    }
                }
            });
            return array;
        }
        if (patch.isObject() && current != null && current.isObject()) {
            ObjectNode merged = (ObjectNode) current.deepCopy();
            patch.properties().forEach(e -> merged.set(e.getKey(), e.getValue()));
            return merged;
        }
        return patch;
    }
}
