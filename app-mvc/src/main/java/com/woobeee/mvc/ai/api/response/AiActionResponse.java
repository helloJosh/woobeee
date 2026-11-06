package com.woobeee.mvc.ai.api.response;

import com.woobeee.mvc.ai.entity.AiRequestActions;

import java.util.Map;

/** AI 가 제안한 등록 한 건. {@code payload} 는 type 마다 모양이 다르다(상품 하나 / 할 일 하나). */
public record AiActionResponse(Long id, String type, String status, Map<String, Object> payload) {

    public static AiActionResponse of(AiRequestActions action) {
        return new AiActionResponse(action.getId(), action.getType().name(), action.getStatus().name(),
                action.getPayload());
    }
}
