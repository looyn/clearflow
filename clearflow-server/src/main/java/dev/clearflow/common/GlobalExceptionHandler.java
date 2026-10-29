package dev.clearflow.common;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 统一异常出口：错误响应体固定为 { message }（前端 request.js 依赖该形状）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Map<String, String>> handleBiz(BizException ex) {
        return ResponseEntity.status(ex.getStatus()).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception ex, HttpServletRequest req) {
        log.error("接口处理异常 {} {}", req.getMethod(), req.getRequestURI(), ex);
        return ResponseEntity.status(500).body(Map.of("message", "服务器内部错误"));
    }
}
