package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-08 — 실패는 3회 미만이면 같은 경로로 재발행하고, 3회째는 DEAD + DLQ 기록이다. */
@SuppressWarnings("unchecked")
class AiFailureHandlerTest {

    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiOutboxRepository outbox = mock(AiOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final AiProperties properties = new AiProperties(null, null, 0, 0, 0, 0L, null, null);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);
    private AiFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AiFailureHandler(requests, outbox, kafka, properties);
    }

    private static com.woobeee.mvc.ai.entity.AiRequests request(UUID id, int attempts) {
        com.woobeee.mvc.ai.entity.AiRequests r = mock(com.woobeee.mvc.ai.entity.AiRequests.class);
        when(r.getId()).thenReturn(id);
        when(r.getAttempts()).thenReturn(attempts);
        return r;
    }

    @Test
    void AI_AC_08_underTheLimit_requeuesWithBackoffThroughTheOutbox() {
        UUID id = UUID.randomUUID();
        when(requests.markFailed(eq(id), eq(1), eq("timeout"), any(), eq(now))).thenReturn(1);

        handler.failOrDie(request(id, 1), "timeout", now);

        // 처리 1회 실패 → 10초 뒤. 재시도 가능 시각이 lease_until 이 된다
        verify(requests).markFailed(id, 1, "timeout", now.plusSeconds(10), now);
        ArgumentCaptor<AiOutbox> event = ArgumentCaptor.forClass(AiOutbox.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getRequestId()).isEqualTo(id);
        // 이벤트는 lease 가 끝난 뒤에 나가야 한다 — 같은 순간이면 claim 이 409 로 건너뛸 수 있다
        assertThat(event.getValue().getNextAttemptAt()).isAfter(now.plusSeconds(10));
        verify(requests, never()).markDead(any(), anyInt(), anyString(), any());
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    void AI_AC_08_atTheLimit_marksDeadAndWritesTheDlqRecord() {
        UUID id = UUID.randomUUID();
        when(requests.markDead(eq(id), eq(3), eq("gave up"), eq(now))).thenReturn(1);

        handler.failOrDie(request(id, 3), "gave up", now);

        verify(requests).markDead(id, 3, "gave up", now);
        verify(outbox, never()).save(any());
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("ai.request.dlq"), eq(id.toString()), value.capture());
        assertThat(value.getValue()).contains(id.toString()).contains("gave up").contains("\"attempts\":3");
    }

    @Test
    void AI_AC_08_aDlqFailureIsSwallowed() {
        UUID id = UUID.randomUUID();
        when(requests.markDead(any(), anyInt(), anyString(), any())).thenReturn(1);
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("no broker"));

        assertThatCode(() -> handler.failOrDie(request(id, 3), "gave up", now)).doesNotThrowAnyException();
    }

    @Test
    void AI_AC_08_theErrorTextIsEscapedInsideTheDlqJson() {
        UUID id = UUID.randomUUID();
        when(requests.markDead(any(), anyInt(), anyString(), any())).thenReturn(1);

        handler.failOrDie(request(id, 3), "bad \"quote\"\nnewline \\", now);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("ai.request.dlq"), anyString(), value.capture());
        assertThat(value.getValue()).contains("bad \\\"quote\\\"\\nnewline \\\\");
    }

    @Test
    void AI_AC_08_aLostRaceDoesNothingElse() {
        UUID id = UUID.randomUUID();
        when(requests.markFailed(any(), anyInt(), anyString(), any(), any())).thenReturn(0);

        handler.failOrDie(request(id, 1), "timeout", now);

        verify(outbox, never()).save(any());
    }
}
