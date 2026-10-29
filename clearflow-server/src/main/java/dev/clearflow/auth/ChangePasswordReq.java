package dev.clearflow.auth;

/** 修改本人密码：需校验原密码 */
public record ChangePasswordReq(String oldPassword, String newPassword) {
}
