package dev.clearflow.member;

import dev.clearflow.common.PageResult;
import dev.clearflow.common.RequireAdmin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/members")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /** 成员列表（服务端分页 + 关键字/角色筛选；含账号、邮箱、角色——仅系统管理员） */
    @GetMapping
    @RequireAdmin
    public PageResult<MemberView> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int pageSize,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String role) {
        return memberService.page(page, pageSize, keyword, role);
    }

    /** 成员池概览（总人数 + 角色候选，SQL 聚合） */
    @GetMapping("/stats")
    @RequireAdmin
    public Map<String, Object> stats() {
        return memberService.stats();
    }

    /** 成员精简列表（id / 姓名 / 头像色 / 职位）：下拉与名称映射用，任意登录人可用 */
    @GetMapping("/brief")
    public java.util.List<MemberBrief> brief() {
        return memberService.briefAll();
    }

    /** 邀请成员（从系统池创建，默认密码由创建人设置） */
    @PostMapping
    @RequireAdmin
    public Map<String, Object> create(@RequestBody MemberCreateReq req) {
        memberService.create(req);
        return Map.of("ok", true);
    }

    /** 编辑成员 */
    @PutMapping("/{id}")
    @RequireAdmin
    public Map<String, Object> update(@PathVariable Long id, @RequestBody MemberUpdateReq req) {
        memberService.update(id, req);
        return Map.of("ok", true);
    }

    /** 重置成员密码 */
    @PutMapping("/{id}/password")
    @RequireAdmin
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestBody PasswordReq req) {
        memberService.resetPassword(id, req.password());
        return Map.of("ok", true);
    }

    /** 移除成员 */
    @DeleteMapping("/{id}")
    @RequireAdmin
    public Map<String, Object> remove(@PathVariable Long id) {
        memberService.remove(id);
        return Map.of("ok", true);
    }
}
