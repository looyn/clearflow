package dev.clearflow.ai;

import dev.clearflow.common.BizException;
import dev.clearflow.common.Jsonz;
import dev.clearflow.requirement.Requirement;
import dev.clearflow.requirement.RequirementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求 → 任务拆分：调用 OpenAI 兼容的 /chat/completions，只产出草稿，不直接落库。
 * 未配置 api-key（或显式 mock=true）时走本地规则模拟，便于无 Key 演示完整流程。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private static final int MAX_TASKS = 10;
    private static final Set<String> TYPES = Set.of("fe", "be", "qa", "de");
    private static final Set<String> PRIOS = Set.of("p0", "p1", "p2");
    private static final String SYSTEM = """
            你是资深研发负责人，擅长把产品需求拆解为可独立执行、可验收的研发任务。
            任务数量由需求实际复杂度决定：简单需求只给 1 个任务，只有存在可独立交付的工作时才拆分。
            只输出 JSON，不要任何解释，不要 Markdown 代码块。""";

    private final RequirementService requirementService;
    private final boolean enabled;
    private final boolean mock;
    private final String chatUrl;
    private final String apiKey;
    private final String model;
    private final long timeoutSeconds;
    private final HttpClient http;

    public AiService(RequirementService requirementService,
                     @Value("${clearflow.ai.enabled:true}") boolean enabled,
                     @Value("${clearflow.ai.mock:false}") boolean mock,
                     @Value("${clearflow.ai.base-url:https://api.openai.com/v1}") String baseUrl,
                     @Value("${clearflow.ai.api-key:}") String apiKey,
                     @Value("${clearflow.ai.model:gpt-4o-mini}") String model,
                     @Value("${clearflow.ai.timeout-seconds:60}") long timeoutSeconds) {
        this.requirementService = requirementService;
        this.enabled = enabled;
        this.mock = mock;
        // base-url 兼容两种写法：版本前缀（.../v1）或完整接口地址（.../v1/chat/completions）
        String base = baseUrl.strip().replaceAll("/+$", "");
        this.chatUrl = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        // 启动时打一次配置结论（不含 Key）：便于确认当前走真实模型还是演示数据
        boolean keyConfigured = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.info("AI 拆分任务：功能已关闭");
        } else if (mock || !keyConfigured) {
            log.info("AI 拆分任务：使用演示数据（mock={}, keyConfigured={}）", mock, keyConfigured);
        } else {
            log.info("AI 拆分任务：已启用真实模型 endpoint={}, model={}", chatUrl, model);
        }
    }

    public SplitResult splitTasks(String requirementId) {
        if (!enabled) {
            throw BizException.bad("AI 功能未启用");
        }
        Requirement.View req = requirementService.view(requirementId);
        // 没有 Key 时自动降级为演示数据，避免前端点开就报错
        if (mock || apiKey == null || apiKey.isBlank()) {
            return new SplitResult(mockTasks(req), true, "mock");
        }
        List<TaskDraft> tasks = parseTasks(callModel(buildPrompt(req)), req);
        if (tasks.isEmpty()) {
            throw BizException.bad("AI 未返回有效任务，请重试");
        }
        return new SplitResult(tasks, false, model);
    }

    private String buildPrompt(Requirement.View req) {
        StringBuilder sb = new StringBuilder();
        sb.append("需求标题：").append(req.title()).append('\n');
        sb.append("需求类型：").append(blankTo(req.type(), "功能"))
                .append("；优先级：").append(blankTo(req.prio(), "未定"))
                .append("；目标迭代：").append(blankTo(req.iter(), "待排期"))
                .append("；预估规模：").append(req.estimate() == null ? "未估" : req.estimate() + " 点")
                .append('\n');
        String desc = limit(plain(req.desc()), 4000);
        if (!desc.isBlank()) {
            sb.append("需求描述：\n").append(desc).append('\n');
        }
        if (!req.acceptance().isEmpty()) {
            sb.append("验收标准：\n");
            int i = 1;
            for (Requirement.AcceptanceView a : req.acceptance()) {
                if (a.title() != null && !a.title().isBlank()) {
                    sb.append(i++).append(". ").append(a.title()).append('\n');
                }
            }
        }
        sb.append("""
                
                请根据需求实际复杂度决定任务数量：能一个任务说清就只给 1 个；只有存在可独立交付的工作时才拆成多个；最多 10 个，不要为了凑数量硬拆。所有验收标准都必须被覆盖。只输出如下 JSON：
                {"tasks":[{"title":"动词开头的任务标题","desc":"<p>具体做什么、关键点</p>","type":"be","prio":"p1","estimate":8}]}
                type 只能取 fe/be/qa/de；prio 只能取 p0/p1/p2；estimate 为单个任务预计投入工时（小时，1-40 的整数）。""");

        return sb.toString();
    }

    private String callModel(String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM),
                Map.of("role", "user", "content", userPrompt)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(chatUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Jsonz.write(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 超时/连不上分开提示，并带上 endpoint 与模型名，便于直接定位配置问题（不含 Key）
            String kind = e instanceof HttpTimeoutException ? "请求超时"
                    : e instanceof ConnectException ? "无法连接" : "连接失败";
            String detail = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getClass().getSimpleName() + "：" + limit(e.getMessage(), 200);
            throw BizException.bad("AI 服务" + kind + "（" + chatUrl + "，model=" + model + "，" + detail + "）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.bad("AI 服务调用被中断");
        }
        if (res.statusCode() / 100 != 2) {
            // 透出服务商的具体报错（不含 Key），404 时能直接看出是地址还是模型名的问题
            throw BizException.bad(aiError(res.statusCode(), res.body()));
        }
        JsonNode root;
        try {
            root = Jsonz.tree(res.body());
        } catch (RuntimeException e) {
            throw BizException.bad("AI 服务返回内容无法解析");
        }
        String content = root == null ? ""
                : emptyIfNull(root.path("choices").path(0).path("message").path("content").asString());
        if (content.isBlank()) {
            throw BizException.bad("AI 返回内容为空，请重试");
        }
        return content;
    }

    /** 把服务商错误收敛成一句可操作提示：带请求地址、模型名和原始 message */
    private String aiError(int status, String body) {
        String detail = "";
        try {
            JsonNode root = Jsonz.tree(body);
            if (root != null) {
                detail = root.path("error").path("message").asString("");
                if (detail.isBlank()) {
                    detail = root.path("message").asString("");
                }
                if (detail.isBlank()) {
                    detail = root.path("error").asString("");
                }
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 错误体（如网关 HTML）直接截取原文
        }
        if (detail.isBlank() && body != null) {
            detail = body.strip().replaceAll("\\s+", " ");
        }
        if (detail.length() > 240) {
            detail = detail.substring(0, 240) + "…";
        }
        String hint = status == 404
                ? "请确认 base-url 含版本前缀（如 /v1），且 model 在该服务商可用"
                : "请检查 Key、base-url 与 model";
        return "AI 服务返回 HTTP " + status + "（" + chatUrl + "，model=" + model + "）"
                + (detail.isBlank() ? "" : "：" + detail) + "。" + hint;
    }

    private List<TaskDraft> parseTasks(String raw, Requirement.View req) {
        JsonNode arr;
        try {
            arr = Jsonz.tree(stripFence(raw)).path("tasks");
        } catch (RuntimeException e) {
            throw BizException.bad("AI 返回格式无法解析，请重试");
        }
        if (arr == null || !arr.isArray()) {
            throw BizException.bad("AI 返回格式无法解析，请重试");
        }
        String fallbackPrio = blankTo(req.prio(), "p1").toLowerCase();
        List<TaskDraft> out = new ArrayList<>();
        for (JsonNode n : arr) {
            String title = n.path("title").asString("").strip();
            if (title.isBlank()) {
                continue;
            }
            String type = n.path("type").asString("").strip().toLowerCase();
            String prio = n.path("prio").asString("").strip().toLowerCase();
            int estimate = parseEstimate(n.path("estimate").asString(""));
            out.add(new TaskDraft(limit(title, 80),
                    limit(n.path("desc").asString("").strip(), 2000),
                    TYPES.contains(type) ? type : "fe",
                    PRIOS.contains(prio) ? prio : fallbackPrio,
                    Math.max(1, Math.min(40, estimate))));
            if (out.size() >= MAX_TASKS) {
                break;
            }
        }
        return out;
    }

    /** 无 Key 演示：按验收条数 + 预估点数粗分复杂度，简单需求只给 1 个任务 */
    private List<TaskDraft> mockTasks(Requirement.View req) {
        String title = req.title();
        String prio = blankTo(req.prio(), "p1").toLowerCase();
        List<String> ac = req.acceptance().stream()
                .map(Requirement.AcceptanceView::title)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        int est = req.estimate() == null ? 0 : req.estimate();
        // 验收条目越多、点数越大，拆分粒度越细
        int score = ac.size() + (est >= 13 ? 2 : est >= 5 ? 1 : 0);
        String type = "技术".equals(req.type()) ? "be" : "fe";

        List<TaskDraft> out = new ArrayList<>();
        if (score <= 1) {
            out.add(new TaskDraft("完成「" + title + "」开发与自测",
                    "<p>按需求完成改动，自测主流程与异常分支。</p>", type, prio,
                    Math.max(2, Math.min(8, est > 0 ? est : 3))));
            return out;
        }

        if ("be".equals(type)) {
            out.add(new TaskDraft("梳理「" + title + "」技术方案",
                    "<p>确认改动范围、数据模型与兼容性风险。</p>", "be", prio, 2));
            out.add(new TaskDraft("实现「" + title + "」核心逻辑",
                    "<p>按方案完成改动，补充必要的单元测试。</p>", "be", prio,
                    Math.max(3, Math.min(13, est > 0 ? est : 5))));
        } else if (score >= 4) {
            out.add(new TaskDraft("后端实现「" + title + "」接口与数据",
                    "<p>实现接口、参数校验与数据变更。</p>", "be", prio, 5));
            out.add(new TaskDraft("前端实现「" + title + "」页面与交互",
                    "<p>完成页面开发，覆盖加载、空态与错误提示。</p>", "fe", prio, 5));
        } else {
            out.add(new TaskDraft("实现「" + title + "」功能",
                    "<p>按需求完成前后端改动。</p>", type, prio,
                    Math.max(3, Math.min(8, est > 0 ? est : 5))));
        }

        StringBuilder qaDesc = new StringBuilder();
        if (ac.isEmpty()) {
            qaDesc.append("<p>前后端联调，自测主流程与异常分支。</p>");
        } else {
            qaDesc.append("<p>联调并逐条验证验收标准：</p><ul>");
            ac.forEach(a -> qaDesc.append("<li>").append(escapeHtml(a)).append("</li>"));
            qaDesc.append("</ul>");
        }
        out.add(new TaskDraft("「" + title + "」联调与自测", qaDesc.toString(), "qa", prio, 3));
        if (score >= 4 || !ac.isEmpty()) {
            out.add(new TaskDraft("「" + title + "」回归测试与验收",
                    "<p>回归关联功能，按验收标准完成验收。</p>", "qa", prio, 2));
        }
        return out;
    }

    private static String stripFence(String raw) {
        String t = raw.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int end = t.lastIndexOf("```");
            if (end >= 0) {
                t = t.substring(0, end);
            }
        }
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        return s >= 0 && e > s ? t.substring(s, e + 1) : t;
    }

    /** 需求描述是富文本：去掉标签与多余空白后再交给模型 */
    private static String plain(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]*>", " ")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static int parseEstimate(String raw) {
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String emptyIfNull(String s) {
        return s == null ? "" : s;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record TaskDraft(String title, String desc, String type, String prio, Integer estimate) {
    }

    public record SplitResult(List<TaskDraft> tasks, boolean mock, String model) {
    }
}
