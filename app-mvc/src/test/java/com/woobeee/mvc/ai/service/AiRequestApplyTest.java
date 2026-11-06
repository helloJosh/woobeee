package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.entity.AiActionStatus;
import com.woobeee.mvc.ai.entity.AiRequestActions;
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
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI-AC-11·12·15 — 승인·거절. */
class AiRequestApplyTest {

    private static final long ME = 7L;

    private final MemberRepository members = mock(MemberRepository.class);
    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final AiOutboxRepository outbox = mock(AiOutboxRepository.class);
    private final AiActionRepository actions = mock(AiActionRepository.class);
    private final AiImageStore store = mock(AiImageStore.class);
    private final AiActionApplier applier = mock(AiActionApplier.class);
    private AiRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiRequestServiceImpl(members, requests, outbox, actions, store,
                new AiProperties(null, null, 0, 0, 0, 0L, null, Set.of(1L)), applier);
        Member me = mock(Member.class);
        when(me.getId()).thenReturn(ME);
        when(members.findByEmail("me@woobeee.com")).thenReturn(Optional.of(me));
        Member other = mock(Member.class);
        when(other.getId()).thenReturn(8L);
        when(members.findByEmail("other@woobeee.com")).thenReturn(Optional.of(other));
        when(store.url(any())).thenReturn("u");
    }

    private AiRequests request(long memberId, AiRequestStatus status) {
        AiRequests r = mock(AiRequests.class);
        UUID id = UUID.randomUUID();
        when(r.getId()).thenReturn(id);
        when(r.getMemberId()).thenReturn(memberId);
        when(r.getStatus()).thenReturn(status);
        when(r.getImageKeys()).thenReturn(List.of());
        when(requests.findById(id)).thenReturn(Optional.of(r));
        when(actions.findByRequestIdOrderById(id)).thenReturn(List.of());
        return r;
    }

    private AiRequestActions action(AiRequests r, long id) {
        AiRequestActions a = mock(AiRequestActions.class);
        when(a.getId()).thenReturn(id);
        UUID requestId = r.getId();
        when(a.getRequestId()).thenReturn(requestId);
        when(a.getStatus()).thenReturn(AiActionStatus.PENDING);
        when(actions.findById(id)).thenReturn(Optional.of(a));
        return a;
    }

    private static void assertCode(Throwable thrown, AiErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(code));
    }

    @Test
    void AI_AC_11_apply_claimsTheActionThenRegistersIt() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        AiRequestActions a = action(r, 3L);
        when(actions.markApplied(3L)).thenReturn(1);
        when(actions.countPending(r.getId())).thenReturn(1L);

        service.apply("me@woobeee.com", r.getId(), 3L);

        InOrder order = inOrder(actions, applier);
        order.verify(actions).markApplied(3L);
        order.verify(applier).apply(ME, a);
        verify(requests, never()).markApplied(any(), any());
    }

    @Test
    void AI_AC_11_apply_aSecondApplyIsAlreadyDoneAndRegistersNothing() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        action(r, 3L);
        when(actions.markApplied(3L)).thenReturn(0);

        assertThatThrownBy(() -> service.apply("me@woobeee.com", r.getId(), 3L))
                .satisfies(t -> assertCode(t, AiErrorCode.ACTION_ALREADY_DONE));

        verify(applier, never()).apply(anyLong(), any());
    }

    @Test
    void AI_AC_11_deciding_theLastProposalSettlesTheRequestAsApplied() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        action(r, 3L);
        when(actions.markApplied(3L)).thenReturn(1);
        when(actions.countPending(r.getId())).thenReturn(0L);

        service.apply("me@woobeee.com", r.getId(), 3L);

        verify(requests).markApplied(any(), any());
    }

    @Test
    void AI_AC_11_reject_isCompareAndSetAndAlsoSettles() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        action(r, 4L);
        when(actions.markRejected(4L)).thenReturn(1);
        when(actions.countPending(r.getId())).thenReturn(0L);

        service.reject("me@woobeee.com", r.getId(), 4L);

        verify(actions).markRejected(4L);
        verify(applier, never()).apply(anyLong(), any());
        verify(requests).markApplied(any(), any());

        when(actions.markRejected(4L)).thenReturn(0);
        assertThatThrownBy(() -> service.reject("me@woobeee.com", r.getId(), 4L))
                .satisfies(t -> assertCode(t, AiErrorCode.ACTION_ALREADY_DONE));
    }

    @Test
    void AI_AC_12_apply_aServiceFailurePropagatesSoTheTransactionRollsBackTheClaim() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        AiRequestActions a = action(r, 3L);
        when(actions.markApplied(3L)).thenReturn(1);
        doThrow(AiErrorCode.APPLY_FORBIDDEN.asException()).when(applier).apply(ME, a);

        assertThatThrownBy(() -> service.apply("me@woobeee.com", r.getId(), 3L))
                .satisfies(t -> assertCode(t, AiErrorCode.APPLY_FORBIDDEN));

        // 예외가 서비스 밖으로 나가야 @Transactional 이 markApplied 를 되돌린다 — 요청도 APPLIED 로 넘기지 않는다
        verify(requests, never()).markApplied(any(), any());
    }

    @Test
    void AI_AC_15_apply_andReject_onlyForTheOwner() {
        AiRequests r = request(ME, AiRequestStatus.DRAFTED);
        action(r, 3L);

        assertThatThrownBy(() -> service.apply("other@woobeee.com", r.getId(), 3L))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.reject("other@woobeee.com", r.getId(), 3L))
                .satisfies(t -> assertCode(t, AiErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> service.apply(null, r.getId(), 3L))
                .satisfies(t -> assertCode(t, AiErrorCode.LOGIN_REQUIRED));

        verify(actions, never()).markApplied(anyLong());
    }

    @Test
    void AI_AC_15_apply_anActionOfAnotherRequestIsNotFound() {
        AiRequests mine = request(ME, AiRequestStatus.DRAFTED);
        AiRequests theirs = request(8L, AiRequestStatus.DRAFTED);
        action(theirs, 9L);

        assertThatThrownBy(() -> service.apply("me@woobeee.com", mine.getId(), 9L))
                .satisfies(t -> assertCode(t, AiErrorCode.ACTION_NOT_FOUND));
        assertThatThrownBy(() -> service.apply("me@woobeee.com", mine.getId(), 12345L))
                .satisfies(t -> assertCode(t, AiErrorCode.ACTION_NOT_FOUND));
    }

    @Test
    void AI_AC_11_apply_onlyOnceTheRequestHasBeenDrafted() {
        for (AiRequestStatus status : List.of(AiRequestStatus.RECEIVED, AiRequestStatus.PROCESSING,
                AiRequestStatus.FAILED, AiRequestStatus.DEAD)) {
            AiRequests r = request(ME, status);
            action(r, 3L);
            assertThatThrownBy(() -> service.apply("me@woobeee.com", r.getId(), 3L)).as(status.name())
                    .satisfies(t -> assertCode(t, AiErrorCode.NOT_READY));
        }
        verify(actions, never()).markApplied(anyLong());
    }
}
