package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.api.response.AiRequestResponse;
import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.ai.repository.AiActionRepository;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AI-AC-01·02·10·15 — 접수·조회·재시도. */
class AiRequestServiceTest {

    private static final long ME = 7L;
    private static final long OTHER = 8L;
    private static final long MANAGER = 1L;

    private final MemberRepository members = mock(MemberRepository.class);
    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiOutboxRepository outbox = mock(AiOutboxRepository.class);
    private final AiActionRepository actions = mock(AiActionRepository.class);
    private final AiImageStore store = mock(AiImageStore.class);
    private final AiProperties properties =
            new AiProperties(null, null, 0, 0, 0, 0L, null, Set.of(MANAGER));
    private AiRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiRequestServiceImpl(members, requests, outbox, actions, store, properties, mock(AiActionApplier.class));
        member("me@woobeee.com", ME);
        member("other@woobeee.com", OTHER);
        member("boss@woobeee.com", MANAGER);
        when(requests.saveAndFlush(any(AiRequests.class))).thenAnswer(inv -> inv.getArgument(0));
        when(store.url(anyString())).thenAnswer(inv -> "https://img/" + inv.getArgument(0));
    }

    private void member(String email, long id) {
        Member m = mock(Member.class);
        when(m.getId()).thenReturn(id);
        when(members.findByEmail(email)).thenReturn(Optional.of(m));
    }

    private static MultipartFile png(String name, int bytes) {
        return new MockMultipartFile("images", name, "image/png", new byte[bytes]);
    }

    private static void assertCode(Throwable thrown, AiErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }

    private void assertNothingWasWritten() {
        verify(store, never()).put(anyString(), any(byte[].class), anyString());
        verify(requests, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void AI_AC_01_create_savesRequestAndOutboxTogether() {
        AiRequestResponse response = service.create("me@woobeee.com", "GO패스 추가해줘",
                List.of(png("a.png", 10), png("b.png", 20)));

        ArgumentCaptor<AiRequests> saved = ArgumentCaptor.forClass(AiRequests.class);
        verify(requests).saveAndFlush(saved.capture());
        AiRequests request = saved.getValue();
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.RECEIVED);
        assertThat(request.getMemberId()).isEqualTo(ME);
        assertThat(request.getImageKeys()).containsExactly(
                "ai/" + request.getId() + "/0", "ai/" + request.getId() + "/1");

        verify(store).put(eq("ai/" + request.getId() + "/0"), any(byte[].class), eq("image/png"));
        verify(store).put(eq("ai/" + request.getId() + "/1"), any(byte[].class), eq("image/png"));

        ArgumentCaptor<AiOutbox> event = ArgumentCaptor.forClass(AiOutbox.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getRequestId()).isEqualTo(request.getId());

        assertThat(response.id()).isEqualTo(request.getId());
        assertThat(response.status()).isEqualTo("RECEIVED");
        assertThat(response.imageUrls()).hasSize(2);
    }

    @Test
    void AI_AC_01_create_aFailedUploadSavesNothingAndCleansUp() {
        doThrow(new IllegalStateException("minio down")).when(store)
                .put(anyString(), any(byte[].class), anyString());

        assertThatThrownBy(() -> service.create("me@woobeee.com", null, List.of(png("a.png", 10))))
                .isInstanceOf(IllegalStateException.class);

        verify(requests, never()).saveAndFlush(any());
        verify(outbox, never()).save(any());
    }

    @Test
    void AI_AC_01_create_aLaterUploadFailureDeletesTheEarlierOnes() {
        doThrow(new IllegalStateException("minio down")).when(store)
                .put(anyString(), any(byte[].class), anyString());
        // 첫 장은 성공시킨다
        org.mockito.Mockito.doNothing().doThrow(new IllegalStateException("minio down")).when(store)
                .put(anyString(), any(byte[].class), anyString());

        assertThatThrownBy(() -> service.create("me@woobeee.com", null, List.of(png("a.png", 10), png("b.png", 10))))
                .isInstanceOf(IllegalStateException.class);

        verify(store).delete(org.mockito.ArgumentMatchers.endsWith("/0"));
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void AI_AC_01_create_rejectsBadInputBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(null, "x", List.of()))
                .satisfies(t -> assertCode(t, AiErrorCode.LOGIN_REQUIRED));
        assertThatThrownBy(() -> service.create("ghost@woobeee.com", "x", List.of()))
                .satisfies(t -> assertCode(t, AiErrorCode.LOGIN_REQUIRED));
        assertThatThrownBy(() -> service.create("me@woobeee.com", "  ", List.of()))
                .satisfies(t -> assertCode(t, AiErrorCode.EMPTY_REQUEST));
        assertThatThrownBy(() -> service.create("me@woobeee.com", null, null))
                .satisfies(t -> assertCode(t, AiErrorCode.EMPTY_REQUEST));
        assertThatThrownBy(() -> service.create("me@woobeee.com", "x",
                List.of(png("1", 1), png("2", 1), png("3", 1), png("4", 1), png("5", 1), png("6", 1))))
                .satisfies(t -> assertCode(t, AiErrorCode.TOO_MANY_IMAGES));
        assertThatThrownBy(() -> service.create("me@woobeee.com", "x", List.of(png("big", 5 * 1024 * 1024 + 1))))
                .satisfies(t -> assertCode(t, AiErrorCode.IMAGE_TOO_LARGE));
        assertThatThrownBy(() -> service.create("me@woobeee.com", "x",
                List.of(new MockMultipartFile("images", "a.gif", "image/gif", new byte[1]))))
                .satisfies(t -> assertCode(t, AiErrorCode.UNSUPPORTED_IMAGE));

        assertNothingWasWritten();
    }

    @Test
    void AI_AC_01_create_exactlyFiveImagesAtTheSizeLimitAreAccepted() {
        List<MultipartFile> five = List.of(png("1", 5 * 1024 * 1024), png("2", 1), png("3", 1), png("4", 1), png("5", 1));

        assertThat(service.create("me@woobeee.com", null, five).imageUrls()).hasSize(5);
    }

    @Test
    void AI_AC_01_create_ignoresEmptyFileParts() {
        MultipartFile empty = new MockMultipartFile("images", "", "application/octet-stream", new byte[0]);

        assertThat(service.create("me@woobeee.com", "글만", List.of(empty)).imageUrls()).isEmpty();
        verify(store, never()).put(anyString(), any(byte[].class), anyString());
    }

    @Test
    void AI_AC_02_create_rejectsWhileAnotherInFlight() {
        when(requests.existsByMemberIdAndStatusIn(eq(ME), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.create("me@woobeee.com", "x", List.of(png("a", 1))))
                .satisfies(t -> assertCode(t, AiErrorCode.REQUEST_IN_PROGRESS));

        assertNothingWasWritten();
    }

    @Test
    void AI_AC_02_create_mapsUniqueViolationToInProgress() {
        when(requests.saveAndFlush(any(AiRequests.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create("me@woobeee.com", "x", List.of(png("a", 1))))
                .satisfies(t -> assertCode(t, AiErrorCode.REQUEST_IN_PROGRESS));

        verify(store).delete(org.mockito.ArgumentMatchers.endsWith("/0"));
        verify(outbox, never()).save(any());
    }

    @Test
    void AI_AC_15_get_onlyForOwner() {
        AiRequests mine = AiRequests.receive(ME, "x", List.of("ai/k/0"));
        when(requests.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(actions.findByRequestIdOrderById(mine.getId())).thenReturn(List.of());

        assertThat(service.get("me@woobeee.com", mine.getId()).imageUrls()).containsExactly("https://img/ai/k/0");
        assertThatThrownBy(() -> service.get("other@woobeee.com", mine.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.get("me@woobeee.com", UUID.randomUUID()))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.get(null, mine.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.LOGIN_REQUIRED));
    }

    @Test
    void AI_AC_15_latest_returnsMyMostRecentRequestOrNull() {
        AiRequests mine = AiRequests.receive(ME, "x", List.of("ai/k/0"));
        when(requests.findFirstByMemberIdOrderByCreatedAtDesc(ME)).thenReturn(Optional.of(mine));
        when(actions.findByRequestIdOrderById(mine.getId())).thenReturn(List.of());

        assertThat(service.latest("me@woobeee.com").id()).isEqualTo(mine.getId());
        assertThat(service.latest("other@woobeee.com")).isNull();
        assertThatThrownBy(() -> service.latest(null)).satisfies(t -> assertCode(t, AiErrorCode.LOGIN_REQUIRED));
    }

    private AiRequests request(long memberId, AiRequestStatus status) {
        AiRequests r = mock(AiRequests.class);
        UUID id = UUID.randomUUID();
        when(r.getId()).thenReturn(id);
        when(r.getMemberId()).thenReturn(memberId);
        when(r.getStatus()).thenReturn(status);
        when(r.getImageKeys()).thenReturn(List.of());
        when(requests.findById(id)).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void AI_AC_10_retry_onlyDeadAndOwnerOrManager() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);
        when(requests.requeueDead(eq(dead.getId()), any())).thenReturn(1);
        when(actions.findByRequestIdOrderById(dead.getId())).thenReturn(List.of());

        service.retry("me@woobeee.com", dead.getId());

        verify(requests).requeueDead(eq(dead.getId()), any());
        ArgumentCaptor<AiOutbox> event = ArgumentCaptor.forClass(AiOutbox.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getRequestId()).isEqualTo(dead.getId());
    }

    @Test
    void AI_AC_10_retry_aManagerMayRetryAnyone() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);
        when(requests.requeueDead(eq(dead.getId()), any())).thenReturn(1);
        when(actions.findByRequestIdOrderById(dead.getId())).thenReturn(List.of());

        service.retry("boss@woobeee.com", dead.getId());

        verify(requests).requeueDead(eq(dead.getId()), any());
    }

    @Test
    void AI_AC_10_retry_strangersSeeNotFoundAndNothingChanges() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);

        assertThatThrownBy(() -> service.retry("other@woobeee.com", dead.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_FOUND));

        verify(requests, never()).requeueDead(any(), any());
        verifyNoInteractions(outbox);
    }

    @Test
    void AI_AC_10_retry_refusesAnythingButDead() {
        for (AiRequestStatus status : List.of(AiRequestStatus.RECEIVED, AiRequestStatus.PROCESSING,
                AiRequestStatus.FAILED, AiRequestStatus.DRAFTED, AiRequestStatus.APPLIED)) {
            AiRequests r = request(ME, status);
            assertThatThrownBy(() -> service.retry("me@woobeee.com", r.getId())).as(status.name())
                    .satisfies(t -> assertCode(t, AiErrorCode.NOT_RETRYABLE));
        }
        verify(requests, never()).requeueDead(any(), any());
    }

    @Test
    void AI_AC_10_retry_lostRaceIsNotRetryable() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);
        when(requests.requeueDead(eq(dead.getId()), any())).thenReturn(0);

        assertThatThrownBy(() -> service.retry("me@woobeee.com", dead.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_RETRYABLE));

        verify(outbox, never()).save(any());
    }

    @Test
    void AI_AC_02_retry_blockedWhileTheOwnerHasAnotherInFlight() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);
        when(requests.existsByMemberIdAndStatusIn(eq(ME), anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.retry("me@woobeee.com", dead.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.REQUEST_IN_PROGRESS));

        verify(requests, never()).requeueDead(any(), any());
    }

    @Test
    void AI_AC_02_retry_mapsUniqueViolationToInProgress() {
        AiRequests dead = request(ME, AiRequestStatus.DEAD);
        when(requests.requeueDead(eq(dead.getId()), any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.retry("me@woobeee.com", dead.getId()))
                .satisfies(t -> assertCode(t, AiErrorCode.REQUEST_IN_PROGRESS));
    }
}
