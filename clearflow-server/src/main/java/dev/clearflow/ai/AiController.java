package dev.clearflow.ai;

import dev.clearflow.common.RequireProject;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiService aiService;

    public AiController(AiService aiService) {
        this.aiService = aiService;
    }

    /** 需求拆分任务草稿：只返回建议，创建仍走 POST /tasks（权限、动态、通知不变） */
    @PostMapping("/requirements/{id}/split-tasks")
    @RequireProject(module = "需求池", level = "查看")
    public AiService.SplitResult splitTasks(@PathVariable String id) {
        return aiService.splitTasks(id);
    }
}
