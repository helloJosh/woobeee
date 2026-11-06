package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.request.ProductDraft;
import com.woobeee.mvc.ai.entity.AiActionType;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.repository.AiActionRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import com.woobeee.mvc.pokemon.api.response.PokemonHostResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonManagedProductResponse;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 도구가 부르는 쪽 — 제안을 <b>기록만</b> 하고(AI-AC-07) 읽기는 요청자 범위로 돌려준다(AI-AC-14).
 * 모든 호출은 처리 중({@code PROCESSING})이고 claim 이 준 attempt 가 현재와 같을 때만 받는다(AI-AC-06).
 */
@Service
@RequiredArgsConstructor
public class AiToolService {
    private static final int MAX_ITEMS = 50;
    private static final int MAX_NAME = 200;

    private final AiRequestRepository requestRepository;
    private final AiActionRepository actionRepository;
    private final AiRequesterScope scope;

    @Transactional
    public void proposeRoundProducts(UUID id, int attempt, Long roundId, List<ProductDraft> items) {
        AiRequests request = requireProcessing(id, attempt);
        validate(roundId, items);
        // 그 사람의 차수인지 먼저 본다 — 남의 차수에는 제안도 쌓이지 않는다.
        scope.roundProducts(scope.emailOf(request.getMemberId()), roundId);

        LocalDateTime now = LocalDateTime.now();
        for (ProductDraft item : items) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("roundId", roundId);
            payload.put("name", item.name().strip());
            payload.put("price", item.price());
            payload.put("coins", item.coins());
            String key = "RP:" + roundId + ":" + sha(normalize(item.name()));
            actionRepository.insertIfAbsent(id, key, AiActionType.ROUND_PRODUCTS.name(), AiJson.write(payload), now);
        }
    }

    @Transactional
    public void proposeTask(UUID id, int attempt, PostTaskRequest task) {
        requireProcessing(id, attempt);
        if (task == null || !StringUtils.hasText(task.name()) || task.name().length() > MAX_NAME) {
            throw AiErrorCode.BAD_REQUEST.asException();
        }
        Map<String, Object> payload = AiJson.canonicalMap(task);
        String json = AiJson.write(payload);
        actionRepository.insertIfAbsent(id, "TASK:" + sha(json), AiActionType.TASK.name(), json, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public PokemonHostResponse myRounds(UUID id, int attempt) {
        return scope.myRounds(scope.emailOf(requireProcessing(id, attempt).getMemberId()));
    }

    @Transactional(readOnly = true)
    public List<PokemonManagedProductResponse> roundProducts(UUID id, int attempt, Long roundId) {
        return scope.roundProducts(scope.emailOf(requireProcessing(id, attempt).getMemberId()), roundId);
    }

    @Transactional(readOnly = true)
    public GetScheduleTreeResponse myScheduleTree(UUID id, int attempt) {
        return scope.scheduleTree(scope.emailOf(requireProcessing(id, attempt).getMemberId()));
    }

    private AiRequests requireProcessing(UUID id, int attempt) {
        AiRequests request = requestRepository.findById(id).orElseThrow(AiErrorCode.NOT_CLAIMABLE::asException);
        if (request.getAttempts() != attempt) {
            throw AiErrorCode.STALE_ATTEMPT.asException();
        }
        if (request.getStatus() != AiRequestStatus.PROCESSING) {
            throw AiErrorCode.NOT_CLAIMABLE.asException();
        }
        return request;
    }

    private static void validate(Long roundId, List<ProductDraft> items) {
        if (roundId == null || items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw AiErrorCode.BAD_REQUEST.asException();
        }
        for (ProductDraft item : items) {
            boolean badName = item == null || !StringUtils.hasText(item.name()) || item.name().strip().length() > MAX_NAME;
            if (badName || !validPrice(item.price()) || item.coins() < 0) {
                throw AiErrorCode.BAD_REQUEST.asException();
            }
        }
    }

    /** 0 보다 크고 소수 둘째 자리까지(스토어 가격이 {@code ₹29.00} 꼴이다). */
    private static boolean validPrice(BigDecimal price) {
        return price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 2;
    }

    /** 대소문자·공백만 다른 이름은 같은 제안이다. */
    private static String normalize(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
