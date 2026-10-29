package dev.clearflow.settings;

import dev.clearflow.common.AuthContext;
import tools.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 成员个人设置（当前仅 notify 通知开关，每人一份，存在 member_setting 表）。
 * 项目级 workflow/options 走 ProjectSettingsController。
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /** 当前登录成员的个人通知开关 */
    @GetMapping
    public Map<String, JsonNode> get() {
        Long me = AuthContext.require().id();
        return Map.of("notify", settingsService.notifyFor(me));
    }

    /** 保存当前登录成员的个人通知分区（section 仅允许 notify） */
    @PutMapping("/{section}")
    public Map<String, Object> save(@PathVariable String section, @RequestBody JsonNode patch) {
        settingsService.saveForMember(AuthContext.require().id(), section, patch);
        return Map.of("ok", true);
    }
}
