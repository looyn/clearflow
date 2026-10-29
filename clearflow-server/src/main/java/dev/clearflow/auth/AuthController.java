package dev.clearflow.auth;

import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.member.Member;
import dev.clearflow.member.MemberService;
import dev.clearflow.member.MemberView;
import dev.clearflow.security.JwtService;
import dev.clearflow.security.MemberPrincipal;
import dev.clearflow.security.SysAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final MemberService memberService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SysAdmin sysAdmin;

    public AuthController(MemberService memberService, PasswordEncoder passwordEncoder, JwtService jwtService,
                          SysAdmin sysAdmin) {
        this.memberService = memberService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sysAdmin = sysAdmin;
    }

    /**
     * 登录：账号可为登录名 / 邮箱 / 姓名
     * 返回 { token, user }（新增契约，前端登录改造时接入）
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        if (req.account() == null || req.account().isBlank()) {
            throw BizException.bad("请输入账号");
        }
        if (req.password() == null || req.password().isBlank()) {
            throw BizException.bad("请输入密码");
        }
        Member member = memberService.findByLogin(req.account().trim());
        if (member == null || member.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), member.getPasswordHash())) {
            throw BizException.unauthorized("账号或密码错误");
        }
        // 系统管理员身份由「内置账号」判定，与 perm 角色无关
        MemberPrincipal principal = new MemberPrincipal(member.getId(), member.getName(), member.getPerm(),
                sysAdmin.is(member));
        return Map.of("token", jwtService.generate(principal), "user", MemberView.of(member, principal.admin()));
    }

    /** 当前登录人 */
    @GetMapping("/me")
    public MemberView me() {
        Member member = memberService.get(AuthContext.require().id());
        return MemberView.of(member, sysAdmin.is(member));
    }

    /** 修改本人密码（校验原密码；管理员重置他人密码走 /members/{id}/password） */
    @PutMapping("/me/password")
    public Map<String, Object> changePassword(@RequestBody ChangePasswordReq req) {
        memberService.changeOwnPassword(AuthContext.require().id(), req.oldPassword(), req.newPassword());
        return Map.of("ok", true);
    }
}
