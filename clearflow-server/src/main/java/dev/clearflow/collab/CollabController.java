package dev.clearflow.collab;

import dev.clearflow.infra.ProjectContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 多态横切端点：评论 / 附件 / 关注 / 实体动态（kind + id 统一挂载） */
@RestController
public class CollabController {

    private final CollabService collabService;
    private final ProjectContext projectContext;

    public CollabController(CollabService collabService, ProjectContext projectContext) {
        this.collabService = collabService;
        this.projectContext = projectContext;
    }

    // ===== 评论 =====

    @GetMapping("/comments")
    public List<CollabService.CommentView> comments(@RequestParam String kind,
                                                    @RequestParam("id") String id) {
        collabService.requireModule(kind, "查看");
        return collabService.comments(kind, id, projectContext.currentProjectId());
    }

    @PostMapping("/comments")
    public Map<String, Object> addComment(@RequestBody Map<String, String> body) {
        return collabService.addComment(body.get("kind"), body.get("eid"), body.get("text"));
    }

    @DeleteMapping("/comments/{id}")
    public Map<String, Object> deleteComment(@PathVariable String id) {
        return collabService.deleteComment(id);
    }

    // ===== 附件 =====

    @PostMapping("/attachments")
    public Map<String, Object> addAttachment(@RequestParam String kind, @RequestParam("id") String id,
                                             @RequestBody Map<String, Object> meta) {
        return collabService.addAttachment(kind, id, meta);
    }

    @DeleteMapping("/attachments")
    public Map<String, Object> removeAttachment(@RequestParam String kind, @RequestParam("id") String id,
                                                @RequestParam("aid") String aid) {
        return collabService.removeAttachment(kind, id, aid);
    }

    // ===== 关注 =====

    @PutMapping("/watch")
    public Map<String, Object> toggleWatch(@RequestParam String kind, @RequestParam("id") String id) {
        return collabService.toggleWatch(kind, id);
    }

    // ===== 实体动态（升序） =====

    @GetMapping("/activity")
    public List<CollabService.ActView> activity(@RequestParam String kind,
                                                @RequestParam("id") String id) {
        collabService.requireModule(kind, "查看");
        return collabService.activity(kind, id, projectContext.currentProjectId());
    }
}
