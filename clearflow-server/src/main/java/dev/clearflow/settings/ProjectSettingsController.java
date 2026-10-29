package dev.clearflow.settings;

import dev.clearflow.common.RequireOwner;
import dev.clearflow.common.RequireProject;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 项目级设置（workflow / options 每项目独立；保存需管理员或项目负责人） */
@RestController
public class ProjectSettingsController {

    private final SettingsService settingsService;

    public ProjectSettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 当前项目的 workflow / options 设置（本项目成员即可读：看板/缺陷等页面的选项字典依赖它） */
    @GetMapping("/projects/{projectId}/settings")
    @RequireProject(member = true, mode = RequireProject.Mode.PARAM, key = "projectId")
    public Map<String, JsonNode> get(@PathVariable Long projectId) {
        return settingsService.getForProject(projectId);
    }

    /** 保存项目级设置分区（仅系统管理员或项目负责人） */
    @PutMapping("/projects/{projectId}/settings/{section}")
    @RequireOwner(mode = RequireOwner.Mode.PARAM, key = "projectId")
    public Map<String, Object> save(@PathVariable Long projectId,
                                    @PathVariable String section,
                                    @RequestBody JsonNode patch) {
        settingsService.saveForProject(projectId, section, patch);
        return Map.of("ok", true);
    }
}
