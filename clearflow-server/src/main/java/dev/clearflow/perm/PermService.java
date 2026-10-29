package dev.clearflow.perm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import dev.clearflow.common.BizException;
import dev.clearflow.security.MemberPrincipal;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限计算：项目内「成员 × 模块」授权优先，缺省按角色模板推导。
 *
 * <p>授权带项目维度（permission_grant.project_id）——同一成员在不同项目可以有不同等级，
 * 系统管理员不经此路径；项目负责人只在被显式授权后受该等级约束（见 ProjectAccessGuard）。</p>
 */
@Service
public class PermService {

    private final PermissionGrantMapper grantMapper;

    public PermService(PermissionGrantMapper grantMapper) {
        this.grantMapper = grantMapper;
    }

    /** 有效等级 = 本项目授权覆盖 | 角色默认（projectId 为空时只按角色默认，用于无项目场景）；
     *  项目负责人未被显式授权时为本项目全权，授权后同样受该等级约束 */
    public String effectiveLevel(MemberPrincipal principal, Long projectId, String module, boolean projectOwner) {
        String grant = grantLevel(projectId, principal.id(), module);
        if (grant != null) {
            return grant;
        }
        if (projectOwner) {
            return PermConstants.OWNER_LEVEL;
        }
        return PermConstants.defaultLevel(principal.perm(), module);
    }

    /** 本项目内的显式授权等级（未设置过返回 null） */
    public String grantLevel(Long projectId, Long memberId, String module) {
        if (projectId == null || memberId == null) {
            return null;
        }
        PermissionGrant grant = grantMapper.selectOne(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getProjectId, projectId)
                .eq(PermissionGrant::getMemberId, memberId)
                .eq(PermissionGrant::getModuleKey, module));
        return grant == null ? null : grant.getLevel();
    }

    /** 系统级常量：模块 / 等级 / 角色 + 角色默认矩阵（不含授权数据） */
    public Map<String, Object> constants() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modules", PermConstants.MODULES);
        result.put("levels", PermConstants.LEVELS);
        result.put("roles", PermConstants.ROLES);
        result.put("defaults", defaultMatrix());
        return result;
    }

    /** 角色默认矩阵（前端展示与「重置为该角色默认」的基线） */
    public Map<String, Map<String, String>> defaultMatrix() {
        Map<String, Map<String, String>> defaults = new LinkedHashMap<>();
        for (String role : PermConstants.ROLES) {
            Map<String, String> byModule = new LinkedHashMap<>();
            for (String module : PermConstants.MODULES) {
                byModule.put(module, PermConstants.defaultLevel(role, module));
            }
            defaults.put(role, byModule);
        }
        return defaults;
    }

    /** 某项目内、指定成员列表的授权（memberId → module → level；只返回显式设置过的） */
    public Map<Long, Map<String, String>> projectGrants(Long projectId, List<Long> memberIds) {
        Map<Long, Map<String, String>> byMember = new LinkedHashMap<>();
        if (projectId == null || memberIds == null || memberIds.isEmpty()) {
            return byMember;
        }
        List<PermissionGrant> grants = grantMapper.selectList(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getProjectId, projectId)
                .in(PermissionGrant::getMemberId, memberIds));
        for (PermissionGrant g : grants) {
            byMember.computeIfAbsent(g.getMemberId(), k -> new LinkedHashMap<>())
                    .put(g.getModuleKey(), g.getLevel());
        }
        return byMember;
    }

    /** 写入项目内授权（PUT /perm/{memberId}?projectId=） */
    public void saveGrant(Long projectId, Long memberId, String moduleKey, String level) {
        if (projectId == null) {
            throw BizException.bad("缺少项目标识");
        }
        if (!PermConstants.MODULES.contains(moduleKey)) {
            throw BizException.bad("无效模块：" + moduleKey);
        }
        if (!PermConstants.LEVELS.contains(level)) {
            throw BizException.bad("无效等级：" + level);
        }
        PermissionGrant existing = grantMapper.selectOne(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getProjectId, projectId)
                .eq(PermissionGrant::getMemberId, memberId)
                .eq(PermissionGrant::getModuleKey, moduleKey));
        if (existing == null) {
            PermissionGrant grant = new PermissionGrant();
            grant.setProjectId(projectId);
            grant.setMemberId(memberId);
            grant.setModuleKey(moduleKey);
            grant.setLevel(level);
            grantMapper.insert(grant);
        } else if (!existing.getLevel().equals(level)) {
            // 联合主键（project_id+member_id+module_key）无 @TableId，updateById 不可用，按条件更新
            grantMapper.update(new LambdaUpdateWrapper<PermissionGrant>()
                    .eq(PermissionGrant::getProjectId, projectId)
                    .eq(PermissionGrant::getMemberId, memberId)
                    .eq(PermissionGrant::getModuleKey, moduleKey)
                    .set(PermissionGrant::getLevel, level));
        }
    }

    /** 成员被删除时清理其全部授权 */
    public void removeByMember(Long memberId) {
        grantMapper.delete(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getMemberId, memberId));
    }

    /** 成员被移出某个项目：只清理该项目内的授权 */
    public void removeByProjectMember(Long projectId, Long memberId) {
        grantMapper.delete(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getProjectId, projectId)
                .eq(PermissionGrant::getMemberId, memberId));
    }

    /** 项目被删除：清理该项目全部授权 */
    public void removeByProject(Long projectId) {
        grantMapper.delete(new LambdaQueryWrapper<PermissionGrant>()
                .eq(PermissionGrant::getProjectId, projectId));
    }
}
