package com.woobeee.mvc.ai.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.TreeMap;

/** 제안 payload 를 JSON 으로 쓰고 읽는 작은 도우미. 기본 설정이면 java.time 은 ISO 문자열이다. */
final class AiJson {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private AiJson() {
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static <T> T convert(Object from, Class<T> to) {
        return MAPPER.convertValue(from, to);
    }

    /** 키 순서를 고정하고 null 을 뺀 맵 — 같은 내용이면 항상 같은 JSON 이 나와 action_key 해시가 안정적이다. */
    static Map<String, Object> canonicalMap(Object value) {
        Map<String, Object> raw = MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() { });
        Map<String, Object> sorted = new TreeMap<>();
        raw.forEach((key, v) -> {
            if (v != null) {
                sorted.put(key, v);
            }
        });
        return sorted;
    }
}
