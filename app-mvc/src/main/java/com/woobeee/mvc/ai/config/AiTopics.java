package com.woobeee.mvc.ai.config;

/** ai 도메인이 쓰는 Kafka 토픽 이름(ADR-005). */
public final class AiTopics {
    /** 요청 이벤트. 키 = requestId. 파티션 1 — 워커가 한 번에 하나씩 처리한다. */
    public static final String REQUEST = "ai.request.v1";
    /** 처리 한도를 넘어 DEAD 가 된 요청의 기록. 아무도 소비하지 않는 사람용 로그다. */
    public static final String DLQ = "ai.request.dlq";

    private AiTopics() {
    }
}
