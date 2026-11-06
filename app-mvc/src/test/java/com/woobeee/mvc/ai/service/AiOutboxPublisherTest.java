package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-03·04 — 아웃박스 발행. */
@SuppressWarnings("unchecked")
class AiOutboxPublisherTest {

    private final AiOutboxRepository outbox = mock(AiOutboxRepository.class);
    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final AiProperties properties = new AiProperties(null, null, 0, 0, 0, 0L, null, null);
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);
    private AiOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new AiOutboxPublisher(outbox, requests, kafka, properties);
    }

    private AiOutbox row(UUID eventId, UUID requestId, int attempts) {
        AiOutbox row = mock(AiOutbox.class);
        when(row.getEventId()).thenReturn(eventId);
        when(row.getRequestId()).thenReturn(requestId);
        when(row.getAttempts()).thenReturn(attempts);
        return row;
    }

    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    private static CompletableFuture<SendResult<String, String>> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
    }

    @Test
    void AI_AC_03_publishOnce_sendsKeyedByRequestIdAndMarksPublished() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        AiOutbox row = row(eventId, requestId, 0);
        when(outbox.lockDue(eq(now), anyInt())).thenReturn(List.of(row));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());

        int published = publisher.publishOnce(now);

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("ai.request.v1"), eq(requestId.toString()), value.capture());
        assertThat(value.getValue()).contains("\"eventId\":\"" + eventId + "\"")
                .contains("\"requestId\":\"" + requestId + "\"")
                .contains("\"type\":\"ai.request\"").contains("\"version\":1");
        verify(outbox).markPublished(eventId);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void AI_AC_03_publishFailure_schedulesBackoffAndContinuesWithTheNextRow() {
        UUID failingEvent = UUID.randomUUID();
        UUID failingRequest = UUID.randomUUID();
        UUID goodEvent = UUID.randomUUID();
        UUID goodRequest = UUID.randomUUID();
        AiOutbox failingRow = row(failingEvent, failingRequest, 0);
        AiOutbox goodRow = row(goodEvent, goodRequest, 0);
        when(outbox.lockDue(eq(now), anyInt())).thenReturn(List.of(failingRow, goodRow));
        when(kafka.send(anyString(), eq(failingRequest.toString()), anyString())).thenReturn(failed());
        when(kafka.send(anyString(), eq(goodRequest.toString()), anyString())).thenReturn(ok());

        int published = publisher.publishOnce(now);

        // 첫 실패: 2^1 × 5초 = 10초 뒤
        verify(outbox).scheduleRetry(failingEvent, 1, now.plusSeconds(10));
        verify(outbox, never()).markPublished(failingEvent);
        verify(outbox).markPublished(goodEvent);
        assertThat(published).isEqualTo(1);
    }

    @Test
    void AI_AC_03_backoffGrowsAndIsCapped() {
        assertThat(AiOutboxPublisher.backoff(1).toSeconds()).isEqualTo(10);
        assertThat(AiOutboxPublisher.backoff(2).toSeconds()).isEqualTo(20);
        assertThat(AiOutboxPublisher.backoff(7).toSeconds()).isEqualTo(600);
        assertThat(AiOutboxPublisher.backoff(30).toSeconds()).isEqualTo(600);
    }

    @Test
    void AI_AC_03_exhaustedAttempts_marksOutboxDeadAndTheRequestDead() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        // 이미 7번 실패했고 8번째도 실패하면 한도(8)에 닿는다
        AiOutbox row = row(eventId, requestId, 7);
        when(outbox.lockDue(eq(now), anyInt())).thenReturn(List.of(row));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed());

        int published = publisher.publishOnce(now);

        verify(outbox).markDead(eventId);
        verify(requests).markPublishFailed(eq(requestId), anyString(), eq(now));
        verify(outbox, never()).scheduleRetry(any(), anyInt(), any());
        assertThat(published).isZero();
    }

    @Test
    void AI_AC_03_aSendThatThrowsSynchronouslyIsTreatedAsAFailure() {
        UUID eventId = UUID.randomUUID();
        AiOutbox row = row(eventId, UUID.randomUUID(), 0);
        when(outbox.lockDue(eq(now), anyInt())).thenReturn(List.of(row));
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("no broker"));

        assertThatCode(() -> publisher.publishOnce(now)).doesNotThrowAnyException();

        verify(outbox).scheduleRetry(eq(eventId), eq(1), any());
    }

    @Test
    void AI_AC_04_publishOnce_readsOnlyThroughTheLockedBatchQuery() {
        when(outbox.lockDue(any(), anyInt())).thenReturn(List.of());

        assertThat(publisher.publishOnce(now)).isZero();

        verify(outbox).lockDue(eq(now), eq(AiOutboxPublisher.BATCH_SIZE));
        verify(outbox, never()).findAll();
    }

    @Test
    void AI_AC_03_kafkaDown_tickNeverThrows() {
        when(outbox.lockDue(any(), anyInt())).thenThrow(new IllegalStateException("db or broker trouble"));

        assertThatCode(publisher::tick).doesNotThrowAnyException();
    }
}
