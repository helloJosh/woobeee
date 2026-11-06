package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.entity.AiRequestActions;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.pokemon.api.request.PokemonProductRequest;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import com.woobeee.mvc.pokemon.service.PokemonService;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.exception.ScheduleException;
import com.woobeee.mvc.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 승인된 제안을 <b>요청자 권한으로</b> 기존 서비스에 넘겨 실제로 등록한다. 권한 검사는 거기에 이미 있으므로
 * 여기서 다시 하지 않는다 — "주최자만 상품 관리", "일정은 본인 것만" 이 그대로 적용된다(AI-AC-11·12).
 *
 * <p>도메인 예외는 ai 에러 코드로 옮긴다: 권한·존재 문제(401/403/404)는 {@code ai_applyForbidden},
 * 그 밖의 규칙 위반(이미 있는 이름 등)은 {@code ai_applyRejected}. 예외는 그대로 밖으로 나가야 호출한
 * 트랜잭션이 승인 표시(CAS)까지 되돌린다.
 */
@Component
@RequiredArgsConstructor
public class AiActionApplier {
    /** ISO 4217 의 "통화 없음". 서비스는 상품 통화를 차수에서 읽으므로 이 값은 쓰이지 않는다. */
    private static final String CURRENCY_FOLLOWS_THE_ROUND = "XXX";

    private final AiRequesterScope scope;
    private final PokemonService pokemonService;
    private final ScheduleService scheduleService;

    public void apply(Long memberId, AiRequestActions action) {
        String email = scope.emailOf(memberId);
        try {
            switch (action.getType()) {
                case ROUND_PRODUCTS -> applyProduct(email, action.getPayload());
                case TASK -> scheduleService.createTask(email, AiJson.convert(action.getPayload(), PostTaskRequest.class));
            }
        } catch (PokemonException ex) {
            throw translate(ex.errorCode().status());
        } catch (ScheduleException ex) {
            throw translate(ex.errorCode().status());
        }
    }

    private void applyProduct(String email, Map<String, Object> payload) {
        Long roundId = ((Number) payload.get("roundId")).longValue();
        BigDecimal price = new BigDecimal(String.valueOf(payload.get("price")));
        int coins = ((Number) payload.get("coins")).intValue();
        pokemonService.createRoundProduct(email, roundId, new PokemonProductRequest(
                String.valueOf(payload.get("name")), CURRENCY_FOLLOWS_THE_ROUND, price, coins, true));
    }

    private static RuntimeException translate(HttpStatus status) {
        boolean notAllowed = status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN
                || status == HttpStatus.NOT_FOUND;
        return (notAllowed ? AiErrorCode.APPLY_FORBIDDEN : AiErrorCode.APPLY_REJECTED).asException();
    }
}
