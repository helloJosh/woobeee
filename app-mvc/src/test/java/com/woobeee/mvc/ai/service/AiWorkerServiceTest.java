package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.ClaimResponse;
import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-05·06·08 — claim·complete·fail. */
class AiWorkerServiceTest {

    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiFailureHandler failures = mock(AiFailureHandler.class);
    private final AiProperties properties = new AiProperties(null, null, 0, 0, 0, 0L, null, null);
    private AiWorkerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiWorkerServiceImpl(requests, failures, properties);
    }

    private AiRequests request(int attempts, AiRequestStatus status) {
        AiRequests r = mock(AiRequests.class);
        UUID id = UUID.randomUUID();
        when(r.getId()).thenReturn(id);
        when(r.getAttempts()).thenReturn(attempts);
        when(r.getStatus()).thenReturn(status);
        when(r.getText()).thenReturn("GO패스 추가");
        when(r.getImageKeys()).thenReturn(List.of("ai/x/0"));
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    private static void assertCode(Throwable thrown, AiErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }

    @Test
    void AI_AC_05_claim_returnsAttemptAndInputsAndGivesTheConfiguredLease() {
        AiRequests r = request(1, AiRequestStatus.PROCESSING);
        when(requests.claim(eq(r.getId()), any(), any())).thenReturn(1);

        ClaimResponse claim = service.claim(r.getId());

        assertThat(claim.attempt()).isEqualTo(1);
        assertThat(claim.text()).isEqualTo("GO패스 추가");
        assertThat(claim.imageKeys()).containsExactly("ai/x/0");
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> lease = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(requests).claim(eq(r.getId()), now.capture(), lease.capture());
        assertThat(Duration.between(now.getValue(), lease.getValue())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void AI_AC_05_claim_conflictWhenNotClaimable() {
        UUID id = UUID.randomUUID();
        when(requests.claim(eq(id), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.claim(id)).satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));
    }

    @Test
    void AI_AC_06_complete_staleAttemptRejected() {
        AiRequests r = request(2, AiRequestStatus.PROCESSING);
        when(requests.complete(eq(r.getId()), eq(1), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.complete(r.getId(), 1, "낡은 보고"))
                .satisfies(t -> assertCode(t, AiErrorCode.STALE_ATTEMPT));
    }

    @Test
    void AI_AC_06_complete_currentAttemptButNotProcessingIsNotClaimable() {
        AiRequests r = request(2, AiRequestStatus.DRAFTED);
        when(requests.complete(eq(r.getId()), eq(2), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.complete(r.getId(), 2, "중복 보고"))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));
    }

    @Test
    void AI_AC_07_complete_truncatesTheSummary() {
        AiRequests r = request(1, AiRequestStatus.PROCESSING);
        when(requests.complete(eq(r.getId()), eq(1), anyString(), any())).thenReturn(1);

        service.complete(r.getId(), 1, "가".repeat(5000));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(requests).complete(eq(r.getId()), eq(1), summary.capture(), any());
        assertThat(summary.getValue()).hasSize(4000);
    }

    @Test
    void AI_AC_06_fail_staleAttemptRejectedAndNothingHandled() {
        AiRequests r = request(2, AiRequestStatus.PROCESSING);

        assertThatThrownBy(() -> service.fail(r.getId(), 1, "timeout"))
                .satisfies(t -> assertCode(t, AiErrorCode.STALE_ATTEMPT));

        verify(failures, never()).failOrDie(any(), anyString(), any());
    }

    @Test
    void AI_AC_08_fail_delegatesToTheFailureHandlerForTheCurrentAttempt() {
        AiRequests r = request(2, AiRequestStatus.PROCESSING);

        service.fail(r.getId(), 2, "timeout");

        verify(failures).failOrDie(eq(r), eq("timeout"), any());
    }

    @Test
    void AI_AC_08_fail_unknownOrSettledRequestIsNotClaimable() {
        AiRequests settled = request(2, AiRequestStatus.DRAFTED);

        assertThatThrownBy(() -> service.fail(settled.getId(), 2, "x"))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));
        assertThatThrownBy(() -> service.fail(UUID.randomUUID(), anyIntValue(), "x"))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_CLAIMABLE));
    }

    private static int anyIntValue() {
        return 1;
    }
}
