package com.woobeee.mvc.ai.entity;

/**
 * 요청의 생애. 진행 중({@link #isInFlight()})인 요청은 사용자당 하나뿐이다(AI-AC-02).
 * {@code FAILED} 는 "다음 재시도를 기다리는 중" 이라 진행 중에 든다.
 */
public enum AiRequestStatus {
    RECEIVED, PROCESSING, DRAFTED, APPLIED, FAILED, DEAD;

    public boolean isInFlight() {
        return this == RECEIVED || this == PROCESSING || this == FAILED;
    }
}
