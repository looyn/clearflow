package dev.clearflow.member;

/** 邀请成员请求体（对应前端邀请成员弹窗） */
public record MemberCreateReq(String name, String email, String role, String perm, String password) {
}
