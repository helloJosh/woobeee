package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.ClaimResponse;
import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiWorkerServiceImpl implements AiWorkerService {
    private static final int MAX_SUMMARY_LENGTH = 4000;

    private final AiRequestRepository requestRepository;
    private final AiFailureHandler failureHandler;
    private final AiProperties properties;

    @Override
    @Transactional
    public ClaimResponse claim(UUID id) {
        LocalDateTime now = LocalDateTime.now();
        if (requestRepository.claim(id, now, now.plus(properties.lease())) == 0) {
            throw AiErrorCode.NOT_CLAIMABLE.asException();
        }
        AiRequests request = requestRepository.findById(id).orElseThrow(AiErrorCode.NOT_CLAIMABLE::asException);
        return new ClaimResponse(request.getId(), request.getAttempts(), request.getText(), request.getImageKeys());
    }

    @Override
    @Transactional
    public void complete(UUID id, int attempt, String summary) {
        String clean = summary == null ? "" : summary.length() > MAX_SUMMARY_LENGTH
                ? summary.substring(0, MAX_SUMMARY_LENGTH) : summary;
        if (requestRepository.complete(id, attempt, clean, LocalDateTime.now()) == 0) {
            throw whyRejected(id, attempt);
        }
    }

    @Override
    @Transactional
    public void fail(UUID id, int attempt, String error) {
        AiRequests request = requestRepository.findById(id).orElseThrow(AiErrorCode.NOT_CLAIMABLE::asException);
        if (request.getAttempts() != attempt) {
            throw AiErrorCode.STALE_ATTEMPT.asException();
        }
        if (request.getStatus() != AiRequestStatus.PROCESSING) {
            throw AiErrorCode.NOT_CLAIMABLE.asException();
        }
        failureHandler.failOrDie(request, error == null ? "" : error, LocalDateTime.now());
    }

    /** 늦게 온 이전 시도의 보고인지(attempt 불일치), 이미 끝난 요청인지를 가려 알려 준다. */
    static RuntimeException whyRejected(AiRequestRepository requests, UUID id, int attempt) {
        return requests.findById(id)
                .filter(current -> current.getAttempts() != attempt)
                .<RuntimeException>map(current -> AiErrorCode.STALE_ATTEMPT.asException())
                .orElseGet(AiErrorCode.NOT_CLAIMABLE::asException);
    }

    private RuntimeException whyRejected(UUID id, int attempt) {
        return whyRejected(requestRepository, id, attempt);
    }
}
