package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.entity.AiActionType;
import com.woobeee.mvc.ai.entity.AiRequestActions;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.pokemon.api.request.PokemonProductRequest;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.service.PokemonService;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import com.woobeee.mvc.schedule.service.ScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-11·12 — 승인된 제안은 요청자 권한으로 기존 서비스를 호출해서만 등록된다. */
class AiActionApplierTest {

    private final AiRequesterScope scope = mock(AiRequesterScope.class);
    private final PokemonService pokemon = mock(PokemonService.class);
    private final ScheduleService schedule = mock(ScheduleService.class);
    private final AiActionApplier applier = new AiActionApplier(scope, pokemon, schedule);

    @BeforeEach
    void setUp() {
        when(scope.emailOf(7L)).thenReturn("me@woobeee.com");
    }

    private static AiRequestActions action(AiActionType type, Map<String, Object> payload) {
        AiRequestActions a = mock(AiRequestActions.class);
        when(a.getType()).thenReturn(type);
        when(a.getPayload()).thenReturn(payload);
        return a;
    }

    @Test
    void AI_AC_11_apply_roundProductCallsCreateRoundProductAsTheRequester() {
        AiRequestActions a = action(AiActionType.ROUND_PRODUCTS,
                Map.of("roundId", 5, "name", "GO패스 디럭스", "price", 29.0, "coins", 0));

        applier.apply(7L, a);

        ArgumentCaptor<PokemonProductRequest> request = ArgumentCaptor.forClass(PokemonProductRequest.class);
        verify(pokemon).createRoundProduct(eq("me@woobeee.com"), eq(5L), request.capture());
        assertThat(request.getValue().name()).isEqualTo("GO패스 디럭스");
        assertThat(request.getValue().price()).isEqualByComparingTo(new BigDecimal("29.00"));
        assertThat(request.getValue().coins()).isZero();
    }

    @Test
    void AI_AC_11_apply_taskCallsCreateTaskAsTheRequester() {
        AiRequestActions a = action(AiActionType.TASK,
                Map.of("name", "치과 예약", "startDate", "2026-11-20", "startTime", "14:30", "reminders", List.of(10, 30)));

        applier.apply(7L, a);

        ArgumentCaptor<PostTaskRequest> request = ArgumentCaptor.forClass(PostTaskRequest.class);
        verify(schedule).createTask(eq("me@woobeee.com"), request.capture());
        assertThat(request.getValue().name()).isEqualTo("치과 예약");
        assertThat(request.getValue().startDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(request.getValue().startTime()).hasHour(14).hasMinute(30);
        assertThat(request.getValue().reminders()).containsExactly(10, 30);
    }

    @Test
    void AI_AC_12_apply_aPermissionFailureBecomesApplyForbidden() {
        when(pokemon.createRoundProduct(any(), any(), any())).thenThrow(PokemonErrorCode.NOT_THE_HOST.asException());

        assertThatThrownBy(() -> applier.apply(7L, action(AiActionType.ROUND_PRODUCTS,
                Map.of("roundId", 5, "name", "x", "price", 1.0, "coins", 0))))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.APPLY_FORBIDDEN));
    }

    @Test
    void AI_AC_12_apply_aNotFoundFromTheDomainIsAlsoForbidden() {
        when(schedule.createTask(any(), any())).thenThrow(ScheduleErrorCode.PROJECT_NOT_FOUND.asException());

        assertThatThrownBy(() -> applier.apply(7L, action(AiActionType.TASK, Map.of("name", "x"))))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.APPLY_FORBIDDEN));
    }

    @Test
    void AI_AC_12_apply_aBusinessRuleFailureBecomesApplyRejected() {
        when(pokemon.createRoundProduct(any(), any(), any())).thenThrow(PokemonErrorCode.DUPLICATE_PRODUCT_NAME.asException());

        assertThatThrownBy(() -> applier.apply(7L, action(AiActionType.ROUND_PRODUCTS,
                Map.of("roundId", 5, "name", "x", "price", 1.0, "coins", 0))))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.APPLY_REJECTED));
    }
}
