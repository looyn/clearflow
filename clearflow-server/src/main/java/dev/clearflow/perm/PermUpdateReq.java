package dev.clearflow.perm;

/** PUT /perm/{memberId} 请求体 */
public record PermUpdateReq(String moduleKey, String level) {
}
