package dev.clearflow.auth;

/** POST /auth/login 请求体 */
public record LoginRequest(String account, String password) {
}
