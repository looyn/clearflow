package dev.clearflow.member;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 成员对外视图（与 mock member 字段一致，不含密码）
 */
public record MemberView(
        Long id,
        String name,
        @JsonProperty("short") String shortName,
        String login,
        String role,
        String color,
        String email,
        String perm,
        String joined,
        Integer capacity,
        boolean admin) {

    public static MemberView of(Member m, boolean admin) {
        String shortName = (m.getName() == null || m.getName().isEmpty())
                ? "?" : m.getName().substring(0, 1);
        return new MemberView(m.getId(), m.getName(), shortName, m.getLogin(), m.getRole(), m.getColor(),
                m.getEmail(), m.getPerm(), m.getJoined(), m.getCapacity(), admin);
    }
}
