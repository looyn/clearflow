package dev.clearflow.member;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 成员精简视图：下拉选择、名称/头像映射、项目成员列表用。
 * 不含账号、邮箱、权限角色等敏感字段——这些只在成员管理页（GET /members，仅系统管理员）返回。
 */
public record MemberBrief(
        Long id,
        String name,
        @JsonProperty("short") String shortName,
        String color,
        String role,
        boolean admin) {

    public static MemberBrief of(Member m, boolean admin) {
        String shortName = (m.getName() == null || m.getName().isEmpty())
                ? "?" : m.getName().substring(0, 1);
        return new MemberBrief(m.getId(), m.getName(), shortName, m.getColor(), m.getRole(), admin);
    }
}
