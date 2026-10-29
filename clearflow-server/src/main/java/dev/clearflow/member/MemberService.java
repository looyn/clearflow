package dev.clearflow.member;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.clearflow.common.AuthContext;
import dev.clearflow.common.BizException;
import dev.clearflow.common.PageResult;
import dev.clearflow.infra.ActivityLog;
import dev.clearflow.infra.UserPreferenceMapper;
import dev.clearflow.perm.PermConstants;
import dev.clearflow.perm.PermService;
import dev.clearflow.project.ProjectMember;
import dev.clearflow.project.ProjectMemberMapper;
import dev.clearflow.security.SysAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MemberService {

    private final MemberMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLog activityLog;
    private final PermService permService;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserPreferenceMapper preferenceMapper;
    private final SysAdmin sysAdmin;

    public MemberService(MemberMapper mapper, PasswordEncoder passwordEncoder, ActivityLog activityLog,
                         PermService permService, ProjectMemberMapper projectMemberMapper,
                         UserPreferenceMapper preferenceMapper, SysAdmin sysAdmin) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.activityLog = activityLog;
        this.permService = permService;
        this.projectMemberMapper = projectMemberMapper;
        this.preferenceMapper = preferenceMapper;
        this.sysAdmin = sysAdmin;
    }

    /** 成员列表（服务端分页 + 关键字/角色筛选，按 id 升序） */
    public PageResult<MemberView> page(int page, int pageSize, String keyword, String role) {
        LambdaQueryWrapper<Member> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(Member::getName, kw).or().like(Member::getEmail, kw)
                    .or().like(Member::getRole, kw));
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(Member::getRole, role.trim());
        }
        wrapper.orderByAsc(Member::getId);
        Page<Member> result = mapper.selectPage(
                new Page<>(Math.max(1, page), Math.min(Math.max(1, pageSize), 200)), wrapper);
        return PageResult.of(result.getRecords().stream()
                        .map(m -> MemberView.of(m, sysAdmin.is(m))).toList(),
                result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /** 成员池概览：总人数 + 角色候选（DISTINCT job_title，避免前端依赖全量列表） */
    public Map<String, Object> stats() {
        long total = mapper.selectCount(null);
        List<String> roles = mapper.selectObjs(new QueryWrapper<Member>()
                        .select("DISTINCT job_title").isNotNull("job_title").ne("job_title", "")
                        .orderByAsc("job_title"))
                .stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("roles", roles);
        return out;
    }

    /** 全部成员的精简视图（任意登录人可用：下拉 / 名称映射 / 项目成员候选） */
    public List<MemberBrief> briefAll() {
        return mapper.selectList(new LambdaQueryWrapper<Member>().orderByAsc(Member::getId)).stream()
                .map(m -> MemberBrief.of(m, sysAdmin.is(m)))
                .toList();
    }

    /** 指定成员的精简视图（按 id 升序，便于展示稳定） */
    public List<MemberBrief> briefOf(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(ids).stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .map(m -> MemberBrief.of(m, sysAdmin.is(m)))
                .toList();
    }

    public Member get(Long id) {
        Member member = mapper.selectById(id);
        if (member == null) {
            throw BizException.notFound("成员不存在");
        }
        return member;
    }

    /** 按登录账号 / 邮箱 / 姓名查找（登录用，含密码哈希） */
    public Member findByLogin(String account) {
        return mapper.selectOne(new LambdaQueryWrapper<Member>()
                .and(w -> w.eq(Member::getLogin, account).or().eq(Member::getEmail, account)
                        .or().eq(Member::getName, account))
                .last("LIMIT 1"));
    }

    @Transactional
    public void create(MemberCreateReq req) {
        if (req.name() == null || req.name().isBlank()) {
            throw BizException.bad("姓名不能为空");
        }
        if (req.email() == null || req.email().isBlank()) {
            throw BizException.bad("邮箱不能为空");
        }
        if (req.password() == null || req.password().length() < 6) {
            throw BizException.bad("密码至少 6 位");
        }
        boolean exists = mapper.exists(new LambdaQueryWrapper<Member>()
                .eq(Member::getEmail, req.email().trim()));
        if (exists) {
            throw BizException.bad("邮箱已存在");
        }
        Member member = new Member();
        member.setName(req.name().trim());
        member.setLogin(req.email().trim());
        member.setEmail(req.email().trim());
        member.setPasswordHash(passwordEncoder.encode(req.password()));
        member.setRole(req.role());
        member.setPerm(validPerm(req.perm()));
        member.setColor("sky");
        member.setJoined(YearMonth.now().format(DateTimeFormatter.ofPattern("yyyy.MM")));
        member.setCapacity(100);
        mapper.insert(member);

        activityLog.write("<b>" + AuthContext.require().name() + "</b> 邀请成员 <b>"
                + member.getName() + "</b> 加入项目");
    }

    @Transactional
    public void update(Long id, MemberUpdateReq req) {
        Member member = get(id);
        if (req.name() != null && !req.name().isBlank()) {
            member.setName(req.name().trim());
        }
        if (req.email() != null && !req.email().isBlank()) {
            boolean exists = mapper.exists(new LambdaQueryWrapper<Member>()
                    .eq(Member::getEmail, req.email().trim()).ne(Member::getId, id));
            if (exists) {
                throw BizException.bad("邮箱已存在");
            }
            member.setEmail(req.email().trim());
        }
        if (req.role() != null) {
            member.setRole(req.role());
        }
        if (req.perm() != null && !req.perm().isBlank()) {
            // 内置系统管理员账号的角色不可改（其系统级能力来自账号身份，与角色无关）
            if (sysAdmin.is(member)) {
                throw BizException.bad("内置系统管理员账号不可修改角色");
            }
            member.setPerm(validPerm(req.perm()));
        }
        if (req.capacity() != null) {
            member.setCapacity(req.capacity());
        }
        if (req.color() != null && !req.color().isBlank()) {
            member.setColor(req.color());
        }
        mapper.updateById(member);
    }

    @Transactional
    public void resetPassword(Long id, String password) {
        Member member = get(id);
        if (password == null || password.length() < 6) {
            throw BizException.bad("密码至少 6 位");
        }
        member.setPasswordHash(passwordEncoder.encode(password));
        mapper.updateById(member);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 重置了成员 <b>"
                + member.getName() + "</b> 的密码");
    }

    /** 修改本人密码：校验原密码后写入新密码（与管理员重置不同，无需 admin 权限） */
    @Transactional
    public void changeOwnPassword(Long id, String oldPassword, String newPassword) {
        Member member = get(id);
        if (oldPassword == null || oldPassword.isBlank()) {
            throw BizException.bad("请输入原密码");
        }
        if (member.getPasswordHash() == null || !passwordEncoder.matches(oldPassword, member.getPasswordHash())) {
            throw BizException.bad("原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.bad("新密码至少 6 位");
        }
        if (passwordEncoder.matches(newPassword, member.getPasswordHash())) {
            throw BizException.bad("新密码不能与原密码相同");
        }
        member.setPasswordHash(passwordEncoder.encode(newPassword));
        mapper.updateById(member);
    }

    /** 删除成员：同时清理项目成员引用、授权、偏好（内置系统管理员账号不可删除） */
    @Transactional
    public void remove(Long id) {
        Member member = get(id);
        if (sysAdmin.is(member)) {
            throw BizException.bad("内置系统管理员账号不可删除");
        }
        mapper.deleteById(id);
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getMemberId, id));
        permService.removeByMember(id);
        preferenceMapper.deleteById(id);
        activityLog.write("<b>" + AuthContext.require().name() + "</b> 移除成员 <b>"
                + member.getName() + "</b>");
    }

    public List<Member> byIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.selectBatchIds(ids);
    }

    /** 角色只能是 PermConstants.ROLES（开发 / 只读）；项目负责人由项目上的 owner 字段承担，不是角色 */
    private String validPerm(String perm) {
        if (perm == null || perm.isBlank()) {
            return PermConstants.ROLES.get(0);
        }
        if (!PermConstants.ROLES.contains(perm)) {
            throw BizException.bad("无效角色：" + perm + "（可选："
                    + String.join(" / ", PermConstants.ROLES) + "）");
        }
        return perm;
    }
}
