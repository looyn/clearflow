package dev.clearflow.common;

import java.util.List;

/**
 * 统一分页响应：{ list, total, page, pageSize }（与设计文档 §6.1 契约一致）
 */
public record PageResult<T>(List<T> list, long total, int page, int pageSize) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }
}
