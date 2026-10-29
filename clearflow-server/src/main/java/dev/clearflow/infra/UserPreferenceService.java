package dev.clearflow.infra;

import org.springframework.stereotype.Service;

/**
 * 用户偏好读写（当前项目，接口保持 PUT /projects/current 等不变）
 */
@Service
public class UserPreferenceService {

    private final UserPreferenceMapper mapper;

    public UserPreferenceService(UserPreferenceMapper mapper) {
        this.mapper = mapper;
    }

    public Long currentProjectId(Long memberId) {
        UserPreference pref = mapper.selectById(memberId);
        return pref == null ? null : pref.getCurrentProjectId();
    }

    public void setCurrentProject(Long memberId, Long projectId) {
        UserPreference pref = upsert(memberId);
        pref.setCurrentProjectId(projectId);
        mapper.updateById(pref);
    }

    private UserPreference upsert(Long memberId) {
        UserPreference pref = mapper.selectById(memberId);
        if (pref == null) {
            pref = new UserPreference();
            pref.setMemberId(memberId);
            mapper.insert(pref);
        }
        return pref;
    }
}
