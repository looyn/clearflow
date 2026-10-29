package dev.clearflow.member;

/** 编辑成员请求体 */
public record MemberUpdateReq(String name, String email, String role, String perm,
                              Integer capacity, String color) {
}
