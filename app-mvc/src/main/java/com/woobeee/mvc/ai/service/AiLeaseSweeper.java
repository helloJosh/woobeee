package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 워커가 죽어 보고조차 없는 요청을 구한다(AI-AC-09). 리스가 끝났는데 아직 PROCESSING 이면 실패 처리해
 * 같은 재시도 경로({@link AiFailureHandler})를 타게 한다. 리스가 살아 있는 요청은 건드리지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiLeaseSweeper {
    private final AiRequestRepository requestRepository;
    private final AiFailureHandler failureHandler;

    @Scheduled(fixedDelayString = "PT60S")
    @Transactional
    public void tick() {
        try {
            sweepOnce(LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("ai lease sweep failed, will retry: {}", ex.toString());
        }
    }

    /** 이번에 실패 처리한 요청 수. 한 건이 실패해도 나머지는 계속한다. */
    @Transactional
    public int sweepOnce(LocalDateTime now) {
        int swept = 0;
        for (AiRequests request : requestRepository.findExpiredProcessing(now)) {
            try {
                failureHandler.failOrDie(request, "lease_expired", now);
                swept++;
            } catch (RuntimeException ex) {
                log.warn("could not sweep ai request {}: {}", request.getId(), ex.toString());
            }
        }
        return swept;
    }
}
