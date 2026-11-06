package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-09 — 리스가 끝난 PROCESSING 은 스위퍼가 실패로 돌린다. */
class AiLeaseSweeperTest {

    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiFailureHandler failures = mock(AiFailureHandler.class);
    private final AiLeaseSweeper sweeper = new AiLeaseSweeper(requests, failures);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);

    @Test
    void AI_AC_09_sweep_failsEachExpiredRequestAsLeaseExpired() {
        AiRequests a = mock(AiRequests.class);
        AiRequests b = mock(AiRequests.class);
        when(requests.findExpiredProcessing(now)).thenReturn(List.of(a, b));

        assertThat(sweeper.sweepOnce(now)).isEqualTo(2);

        verify(failures).failOrDie(a, "lease_expired", now);
        verify(failures).failOrDie(b, "lease_expired", now);
    }

    @Test
    void AI_AC_09_sweep_touchesNothingWhenNoLeaseHasExpired() {
        when(requests.findExpiredProcessing(now)).thenReturn(List.of());

        assertThat(sweeper.sweepOnce(now)).isZero();

        verify(failures, never()).failOrDie(any(), anyString(), any());
    }

    @Test
    void AI_AC_09_oneBadRowDoesNotStopTheSweep() {
        AiRequests a = mock(AiRequests.class);
        AiRequests b = mock(AiRequests.class);
        when(requests.findExpiredProcessing(now)).thenReturn(List.of(a, b));
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(failures).failOrDie(eq(a), anyString(), any());

        assertThatCode(() -> sweeper.sweepOnce(now)).doesNotThrowAnyException();

        verify(failures).failOrDie(b, "lease_expired", now);
    }

    @Test
    void AI_AC_09_tickNeverThrows() {
        when(requests.findExpiredProcessing(any())).thenThrow(new IllegalStateException("db"));

        assertThatCode(sweeper::tick).doesNotThrowAnyException();
    }
}
