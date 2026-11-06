package com.woobeee.mvc.ai.api.response;

import java.util.List;
import java.util.UUID;

/** claim 한 워커가 받는 작업 입력. {@code attempt} 는 이후 모든 보고에 되돌려 보내야 하는 펜싱 번호다(AI-AC-06). */
public record ClaimResponse(UUID id, int attempt, String text, List<String> imageKeys) {
}
