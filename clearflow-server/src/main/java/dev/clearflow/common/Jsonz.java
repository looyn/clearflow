package dev.clearflow.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Jackson 3 JSON 工具（Spring Boot 4 默认 Jackson 3：tools.jackson 包）
 */
public final class Jsonz {

    private static final JsonMapper M = JsonMapper.builder().build();

    private Jsonz() {
    }

    public static String write(Object value) {
        return M.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return M.readValue(json, type);
    }

    public static JsonNode tree(String json) {
        return M.readTree(json);
    }

    /** JSON 字符串数组 → List<String>（null/空安全） */
    public static List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        JsonNode node = M.readTree(json);
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    /** 看起来是对象/数组 JSON（用于复盘字段等“纯文本或 JSON”二态值解析） */
    public static boolean looksStructured(String s) {
        if (s == null) {
            return false;
        }
        String t = s.strip();
        return t.startsWith("{") || t.startsWith("[");
    }
}
