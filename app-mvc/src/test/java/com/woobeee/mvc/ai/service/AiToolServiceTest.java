package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.request.ProductDraft;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.ai.repository.AiActionRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import com.woobeee.mvc.pokemon.api.response.PokemonHostResponse;
import com.woobeee.mvc.schedule.api.request.PostTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-06·07·14 — 제안 기록과 읽기 도구. */
class AiToolServiceTest {

    private static final long ME = 7L;

    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiActionRepository actions = mock(AiActionRepository.class);
    private final AiRequesterScope scope = mock(AiRequesterScope.class);
    private AiToolService service;

    @BeforeEach
    void setUp() {
        service = new AiToolService(requests, actions, scope);
        when(scope.emailOf(ME)).thenReturn("me@woobeee.com");
    }

    private AiRequests processing(int attempts) {
        AiRequests r = mock(AiRequests.class);
        UUID id = UUID.randomUUID();
        when(r.getId()).thenReturn(id);
        when(r.getMemberId()).thenReturn(ME);
        when(r.getAttempts()).thenReturn(attempts);
        when(r.getStatus()).thenReturn(AiRequestStatus.PROCESSING);
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private static void assertCode(Throwable thrown, AiErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }

    private static ProductDraft draft(String name) {
        return new ProductDraft(name, new BigDecimal("29.00"), 0);
    }

    @Test
    void AI_AC_07_proposeRoundProducts_recordsOneActionPerItemAsPendingProposals() {
        AiRequests r = processing(1);

        service.proposeRoundProducts(r.getId(), 1, 5L,
                List.of(draft("GO패스 디럭스"), new ProductDraft("500 코인", new BigDecimal("149"), 500)));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(actions, times(2)).insertIfAbsent(eq(r.getId()), key.capture(), eq("ROUND_PRODUCTS"),
                payload.capture(), any());
        assertThat(key.getAllValues()).doesNotHaveDuplicates().allSatisfy(k -> assertThat(k).hasSizeLessThanOrEqualTo(100));
        assertThat(payload.getAllValues().get(0)).contains("\"roundId\":5").contains("GO패스 디럭스").contains("29");
        assertThat(payload.getAllValues().get(1)).contains("\"coins\":500");
    }

    @Test
    void AI_AC_07_propose_sameProductOnlyDiffersInCaseAndSpacingGetsTheSameKey() {
        AiRequests r = processing(1);

        service.proposeRoundProducts(r.getId(), 1, 5L, List.of(draft("GO패스  디럭스")));
        service.proposeRoundProducts(r.getId(), 1, 5L, List.of(draft(" go패스 디럭스 ")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(actions, times(2)).insertIfAbsent(eq(r.getId()), key.capture(), anyString(), anyString(), any());
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1));
    }

    @Test
    void AI_AC_07_propose_theSameNameOnAnotherRoundIsADifferentProposal() {
        AiRequests r = processing(1);

        service.proposeRoundProducts(r.getId(), 1, 5L, List.of(draft("GO패스")));
        service.proposeRoundProducts(r.getId(), 1, 6L, List.of(draft("GO패스")));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(actions, times(2)).insertIfAbsent(eq(r.getId()), key.capture(), anyString(), anyString(), any());
        assertThat(key.getAllValues().get(0)).isNotEqualTo(key.getAllValues().get(1));
    }

    @Test
    void AI_AC_06_propose_staleAttemptOrNotProcessingIsRejected() {
        AiRequests r = processing(2);

        assertThatThrownBy(() -> service.proposeRoundProducts(r.getId(), 1, 5L, List.of(draft("x"))))
                .satisfies(t -> assertCode(t, AiErrorCode.STALE_ATTEMPT));
        assertThatThrownBy(() -> service.proposeTask(r.getId(), 1, new PostTaskRequest(null, null, "병원", null, null, null, null, null, null, null)))
                .satisfies(t -> assertCode(t, AiErrorCode.STALE_ATTEMPT));
        assertThatThrownBy(() -> service.myRounds(r.getId(), 1))
                .satisfies(t -> assertCode(t, AiErrorCode.STALE_ATTEMPT));

        AiRequests drafted = mock(AiRequests.class);
        UUID id = UUID.randomUUID();
        when(drafted.getAttempts()).thenReturn(2);
        when(drafted.getStatus()).thenReturn(AiRequestStatus.DRAFTED);
        when(requests.findById(id)).thenReturn(Optional.of(drafted));
        assertThatThrownBy(() -> service.proposeRoundProducts(id, 2, 5L, List.of(draft("x"))))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));
        assertThatThrownBy(() -> service.myScheduleTree(UUID.randomUUID(), 1))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));

        verify(actions, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void AI_AC_14_proposeRoundProducts_rejectsARoundTheRequesterDoesNotHost() {
        AiRequests r = processing(1);
        when(scope.roundProducts("me@woobeee.com", 9L)).thenThrow(AiErrorCode.APPLY_FORBIDDEN.asException());

        assertThatThrownBy(() -> service.proposeRoundProducts(r.getId(), 1, 9L, List.of(draft("x"))))
                .satisfies(t -> assertCode(t, AiErrorCode.APPLY_FORBIDDEN));

        verify(actions, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void AI_AC_17_proposeRoundProducts_rejectsInvalidItemsBeforeAnyWrite() {
        AiRequests r = processing(1);
        List<List<ProductDraft>> bad = List.of(
                List.of(),
                List.of(new ProductDraft(" ", new BigDecimal("1"), 0)),
                List.of(new ProductDraft("x", new BigDecimal("0"), 0)),
                List.of(new ProductDraft("x", new BigDecimal("-1"), 0)),
                List.of(new ProductDraft("x", new BigDecimal("1.234"), 0)),
                List.of(new ProductDraft("x", new BigDecimal("1"), -1)),
                java.util.Collections.nCopies(51, draft("x")));

        for (List<ProductDraft> items : bad) {
            assertThatThrownBy(() -> service.proposeRoundProducts(r.getId(), 1, 5L, items))
                    .satisfies(t -> assertCode(t, AiErrorCode.BAD_REQUEST));
        }
        verify(actions, never()).insertIfAbsent(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void AI_AC_07_proposeTask_recordsATaskActionWithTheFieldsAndAStableKey() {
        AiRequests r = processing(1);
        PostTaskRequest task = new PostTaskRequest(null, null, "치과 예약", null, LocalDate.of(2026, 11, 20), null, null, null, null, null);

        service.proposeTask(r.getId(), 1, task);
        service.proposeTask(r.getId(), 1, task);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(actions, times(2)).insertIfAbsent(eq(r.getId()), key.capture(), eq("TASK"), payload.capture(), any());
        assertThat(key.getAllValues().get(0)).isEqualTo(key.getAllValues().get(1)).hasSizeLessThanOrEqualTo(100);
        assertThat(payload.getValue()).contains("치과 예약").contains("2026-11-20");
    }

    @Test
    void AI_AC_17_proposeTask_rejectsABlankOrOverlongName() {
        AiRequests r = processing(1);

        assertThatThrownBy(() -> service.proposeTask(r.getId(), 1, new PostTaskRequest(null, null, " ", null, null, null, null, null, null, null)))
                .satisfies(t -> assertCode(t, AiErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> service.proposeTask(r.getId(), 1, new PostTaskRequest(null, null, "가".repeat(201), null, null, null, null, null, null, null)))
                .satisfies(t -> assertCode(t, AiErrorCode.BAD_REQUEST));
    }

    @Test
    void AI_AC_14_readTools_alwaysUseTheRequestersIdentityFromTheRequest() {
        AiRequests r = processing(1);
        PokemonHostResponse mine = new PokemonHostResponse("me", "나", true, null, List.of());
        when(scope.myRounds("me@woobeee.com")).thenReturn(mine);

        assertThat(service.myRounds(r.getId(), 1)).isSameAs(mine);
        service.roundProducts(r.getId(), 1, 5L);
        service.myScheduleTree(r.getId(), 1);

        verify(scope, times(3)).emailOf(ME); // 세 번 읽었고, 매번 요청의 회원에서 이메일을 구했다
        verify(scope).roundProducts("me@woobeee.com", 5L);
        verify(scope).scheduleTree("me@woobeee.com");
    }

    @Test
    void AI_AC_14_aScopeFailureOnReadPropagatesAndWritesNothing() {
        AiRequests r = processing(1);
        doThrow(AiErrorCode.APPLY_FORBIDDEN.asException()).when(scope).roundProducts(anyString(), eq(9L));

        assertThatThrownBy(() -> service.roundProducts(r.getId(), 1, 9L))
                .satisfies(t -> assertCode(t, AiErrorCode.APPLY_FORBIDDEN));
    }
}
