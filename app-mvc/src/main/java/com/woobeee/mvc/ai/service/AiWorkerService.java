package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.ClaimResponse;

import java.util.UUID;

/** 워커가 내부 API 로 쓰는 쪽. 모든 보고는 claim 이 준 {@code attempt} 가 현재와 같을 때만 받는다(펜싱). */
public interface AiWorkerService {

    /** 잡을 수 없으면(이미 처리됐거나 리스가 살아 있음) 409 {@code ai_notClaimable}. */
    ClaimResponse claim(UUID id);

    void complete(UUID id, int attempt, String summary);

    void fail(UUID id, int attempt, String error);
}
