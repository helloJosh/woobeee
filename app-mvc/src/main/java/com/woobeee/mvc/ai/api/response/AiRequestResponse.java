package com.woobeee.mvc.ai.api.response;

import com.woobeee.mvc.ai.entity.AiRequests;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record AiRequestResponse(
        UUID id,
        String status,
        String text,
        List<String> imageUrls,
        String summary,
        String lastError,
        int attempts,
        List<AiActionResponse> actions,
        LocalDateTime createdAt
) {
    public static AiRequestResponse of(AiRequests request, List<String> imageUrls, List<AiActionResponse> actions) {
        return new AiRequestResponse(request.getId(), request.getStatus().name(), request.getText(), imageUrls,
                request.getSummary(), request.getLastError(), request.getAttempts(), actions, request.getCreatedAt());
    }
}
