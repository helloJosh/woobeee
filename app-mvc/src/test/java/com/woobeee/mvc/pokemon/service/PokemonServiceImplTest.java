package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.PokemonProperties;
import com.woobeee.mvc.pokemon.api.request.PatchPokemonOrderStatusRequest;
import com.woobeee.mvc.pokemon.api.request.PatchPokemonRoundStatusRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonCommentRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonRoundRequest;
import com.woobeee.mvc.pokemon.api.response.PokemonCommentResponse;
import com.woobeee.mvc.pokemon.entity.PokemonOrderComments;
import com.woobeee.mvc.pokemon.entity.PokemonOrderStatus;
import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import com.woobeee.mvc.pokemon.entity.PokemonRateMode;
import com.woobeee.mvc.pokemon.entity.PokemonRoundStatus;
import com.woobeee.mvc.pokemon.entity.PokemonRounds;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import com.woobeee.mvc.pokemon.rate.ExchangeRate;
import com.woobeee.mvc.pokemon.rate.ExchangeRateService;
import com.woobeee.mvc.pokemon.repository.PokemonHostRepository;
import com.woobeee.mvc.pokemon.repository.PokemonOrderCommentRepository;
import com.woobeee.mvc.pokemon.repository.PokemonOrderItemRepository;
import com.woobeee.mvc.pokemon.repository.PokemonOrderRepository;
import com.woobeee.mvc.pokemon.repository.PokemonProductRepository;
import com.woobeee.mvc.pokemon.repository.PokemonRoundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 누가 무엇을 고치고 지울 수 있는가. POKEMON-AC-09·12·13·36·37.
 *
 * <p>이 클래스가 있기 전까지 권한 규칙을 지키는 테스트는 {@code PokemonPropertiesTest} 뿐이었다.
 * 그것은 <b>명단 판정</b>만 보므로, {@code assertCanModify} 의 {@code if (canManage) return;}
 * 한 줄을 지워도, {@code changeStatus} 의 {@code requireHostOfRound} 를 통째로 지워도 초록이었다.
 * 여기서는 실제 서비스 메서드를 불러 거부되는지를 본다.
 *
 * <p>거부 테스트는 예외만 보지 않고 <b>거부 전에 아무것도 건드리지 않았는지</b>까지 확인한다 —
 * 권한 검사가 쓰기 뒤로 밀려 "지우고 나서 거절" 이 되면 예외만으로는 통과해 버린다.
 */
@ExtendWith(MockitoExtension.class)
class PokemonServiceImplTest {

    /** 이 차수를 연 사람. 자기 차수만 주무른다. */
    private static final long HOST_ID = 10L;
    private static final String HOST_LOGIN = "host@example.com";

    /** 전역 운영자 — application.yaml 의 기본 명단은 이 한 명뿐이다 (POKEMON-AC-37). */
    private static final long MANAGER_ID = 1L;
    private static final String MANAGER_LOGIN = "manager@example.com";

    /** 신청서를 낸 사람. 자기 신청서만, 그것도 입금 확인 전까지만 손댄다. */
    private static final long APPLICANT_ID = 42L;
    private static final String APPLICANT_LOGIN = "applicant@example.com";

    /** 로그인은 했지만 이 차수와 아무 관계가 없는 회원. */
    private static final long STRANGER_ID = 99L;
    private static final String STRANGER_LOGIN = "stranger@example.com";

    /** 비로그인. loginId 헤더가 아예 없는 요청이다. */
    private static final String GUEST_LOGIN = null;

    private static final long ROUND_ID = 7L;
    private static final long ORDER_ID = 70L;
    private static final long COMMENT_ID = 700L;

    @Mock PokemonRoundRepository roundRepository;
    @Mock PokemonHostRepository hostRepository;
    @Mock PokemonOrderRepository orderRepository;
    @Mock PokemonOrderItemRepository itemRepository;
    @Mock PokemonOrderCommentRepository commentRepository;
    @Mock PokemonProductRepository productRepository;
    @Mock MemberRepository memberRepository;
    @Mock PokemonMemberResolver memberResolver;
    @Mock ExchangeRateService exchangeRateService;

    private PokemonServiceImpl pokemonService;

    @BeforeEach
    void setUp() {
        // 운영자 명단만은 가짜가 아니라 진짜를 쓴다 — 명단 자체가 이 테스트의 대상이다.
        pokemonService = new PokemonServiceImpl(
                roundRepository, hostRepository, orderRepository, itemRepository,
                commentRepository, productRepository, memberRepository, memberResolver,
                exchangeRateService, new PokemonProperties(Set.of(MANAGER_ID)));
    }

    /* ===== 차수 — 주최자이거나 전역 운영자 (POKEMON-AC-09) ===== */

    @Nested
    class 차수 {

        /** POKEMON-AC-09 — 자기가 연 차수는 주최자가 고친다. */
        @Test
        void theHostCanEditTheirOwnRound() {
            viewerIs(HOST_LOGIN, HOST_ID);
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            assertThatCode(() -> pokemonService.updateRound(HOST_LOGIN, ROUND_ID, roundEdit()))
                    .doesNotThrowAnyException();
        }

        /** POKEMON-AC-09 — 전역 운영자는 남의 차수도 고친다. 잘못 열린 것을 대신 고쳐 줘야 한다. */
        @Test
        void theGlobalManagerCanEditSomeoneElsesRound() {
            viewerIs(MANAGER_LOGIN, MANAGER_ID);
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            assertThatCode(() -> pokemonService.updateRound(MANAGER_LOGIN, ROUND_ID, roundEdit()))
                    .doesNotThrowAnyException();
        }

        /** POKEMON-AC-09 */
        @Test
        void aStrangerCannotEditTheRound() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            PokemonRounds round = round();
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round));

            assertThatThrownBy(() -> pokemonService.updateRound(STRANGER_LOGIN, ROUND_ID, roundEdit()))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_THE_HOST);
            // 거부가 수정 뒤로 밀리면 예외는 나도 값은 이미 바뀌어 있다.
            assertThat(round.getBankAccount()).isEqualTo("하나은행 111 주최자");
        }

        /** POKEMON-AC-09 — 비회원은 회원 id 가 없다. null 이 주최자나 운영자로 통과하면 안 된다. */
        @Test
        void aGuestCannotEditTheRound() {
            viewerIs(GUEST_LOGIN, null);
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            assertThatThrownBy(() -> pokemonService.updateRound(GUEST_LOGIN, ROUND_ID, roundEdit()))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_THE_HOST);
        }

        /** POKEMON-AC-09 */
        @Test
        void theHostCanChangeTheRoundStatus() {
            viewerIs(HOST_LOGIN, HOST_ID);
            PokemonRounds round = round();
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round));
            when(exchangeRateService.current("INR")).thenReturn(
                    new ExchangeRate("INR", new BigDecimal("14.13"), LocalDateTime.now(), false));

            pokemonService.changeRoundStatus(HOST_LOGIN, ROUND_ID,
                    new PatchPokemonRoundStatusRequest(PokemonRoundStatus.CLOSED));

            assertThat(round.getStatus()).isEqualTo(PokemonRoundStatus.CLOSED);
        }

        /** POKEMON-AC-09 — 남이 상태를 옮기면 환율을 묻기도 전에 거절한다. */
        @Test
        void aStrangerCannotChangeTheRoundStatus() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            PokemonRounds round = round();
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round));

            assertThatThrownBy(() -> pokemonService.changeRoundStatus(STRANGER_LOGIN, ROUND_ID,
                    new PatchPokemonRoundStatusRequest(PokemonRoundStatus.PURCHASED)))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_THE_HOST);
            assertThat(round.getStatus()).isEqualTo(PokemonRoundStatus.OPEN);
            verify(exchangeRateService, never()).current(any());
        }

        /** POKEMON-AC-09 */
        @Test
        void aStrangerCannotDeleteTheRound() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            assertThatThrownBy(() -> pokemonService.deleteRound(STRANGER_LOGIN, ROUND_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_THE_HOST);
            verify(roundRepository, never()).delete(any());
        }

        /**
         * POKEMON-AC-09 — 주최자라도 신청서가 들어온 차수는 지우지 못한다. 남의 신청을
         * 통째로 날리는 일이라, 권한이 있다고 열어 줄 수 없다.
         */
        @Test
        void aRoundWithOrdersIsNotDeletedEvenByItsHost() {
            viewerIs(HOST_LOGIN, HOST_ID);
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));
            when(orderRepository.findAllByRoundIdOrderByCreatedAtDesc(ROUND_ID))
                    .thenReturn(List.of(order(APPLICANT_ID, PokemonOrderStatus.ORDERED)));

            assertThatThrownBy(() -> pokemonService.deleteRound(HOST_LOGIN, ROUND_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.ROUND_HAS_ORDERS);
            verify(roundRepository, never()).delete(any());
        }
    }

    /* ===== 신청서 — 주최자는 언제든, 본인은 입금 확인 전까지 (POKEMON-AC-12) ===== */

    @Nested
    class 신청서 {

        /** POKEMON-AC-12 */
        @Test
        void theApplicantCanDeleteTheirOwnOrderWhileItIsStillOrdered() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            PokemonOrders order = order(APPLICANT_ID, PokemonOrderStatus.ORDERED);
            givenOrderAndRound(order);

            pokemonService.deleteOrder(APPLICANT_LOGIN, ORDER_ID);

            verify(orderRepository).delete(order);
        }

        /** POKEMON-AC-12 — 입금이 확인된 뒤에는 본인도 거둘 수 없다. 이미 돈이 들어왔다. */
        @Test
        void theApplicantCannotDeleteTheirOrderOnceTheDepositIsConfirmed() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            givenOrderAndRound(order(APPLICANT_ID, PokemonOrderStatus.DEPOSIT_CONFIRMED));

            assertThatThrownBy(() -> pokemonService.deleteOrder(APPLICANT_LOGIN, ORDER_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.ALREADY_SETTLED);
            verify(orderRepository, never()).delete(any());
        }

        /** POKEMON-AC-12 — 주최자는 입금 확인 뒤에도 지운다. 정산을 책임지는 쪽이다. */
        @Test
        void theHostCanDeleteSomeoneElsesOrderEvenAfterTheDepositIsConfirmed() {
            viewerIs(HOST_LOGIN, HOST_ID);
            PokemonOrders order = order(APPLICANT_ID, PokemonOrderStatus.DEPOSIT_CONFIRMED);
            givenOrderAndRound(order);

            pokemonService.deleteOrder(HOST_LOGIN, ORDER_ID);

            verify(orderRepository).delete(order);
        }

        /** POKEMON-AC-12 */
        @Test
        void aStrangerCannotDeleteSomeoneElsesOrder() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            givenOrderAndRound(order(APPLICANT_ID, PokemonOrderStatus.ORDERED));

            assertThatThrownBy(() -> pokemonService.deleteOrder(STRANGER_LOGIN, ORDER_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            verify(orderRepository, never()).delete(any());
            // 자식부터 지우는 코드가 권한 검사보다 앞서면 신청서만 남고 항목이 사라진다.
            verify(itemRepository, never()).deleteAllByOrderId(any());
        }

        /** POKEMON-AC-12 */
        @Test
        void aGuestCannotDeleteAnOrder() {
            viewerIs(GUEST_LOGIN, null);
            givenOrderAndRound(order(APPLICANT_ID, PokemonOrderStatus.ORDERED));

            assertThatThrownBy(() -> pokemonService.deleteOrder(GUEST_LOGIN, ORDER_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            verify(orderRepository, never()).delete(any());
        }

        /**
         * POKEMON-AC-12 — 주인이 없는 신청서(회원 전용으로 바꾸기 전 비회원이 낸 것)는
         * 본인 확인이 성립하지 않는다. 비회원이 그 신청서를 자기 것이라 주장할 수 없어야 한다.
         */
        @Test
        void anOwnerlessOrderIsNotClaimableByAGuest() {
            viewerIs(GUEST_LOGIN, null);
            givenOrderAndRound(order(null, PokemonOrderStatus.ORDERED));

            assertThatThrownBy(() -> pokemonService.deleteOrder(GUEST_LOGIN, ORDER_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            verify(orderRepository, never()).delete(any());
        }

        /** POKEMON-AC-12 — 수정도 삭제와 같은 규칙이다. 거부는 항목을 지우기 전에 난다. */
        @Test
        void aStrangerCannotEditSomeoneElsesOrder() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            givenOrderAndRound(order(APPLICANT_ID, PokemonOrderStatus.ORDERED));

            assertThatThrownBy(() -> pokemonService.updateOrder(STRANGER_LOGIN, ORDER_ID, orderEdit()))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            verify(itemRepository, never()).deleteAllByOrderId(any());
        }

        /**
         * POKEMON-AC-09 — 진행 상태는 <b>주최자만</b> 옮긴다. 본인 신청서라도 "입금했어요" 를
         * 스스로 눌러 입금확인으로 넘길 수는 없다 — 돈이 들어왔는지는 받는 쪽이 판단한다.
         */
        @Test
        void theApplicantCannotChangeTheirOwnOrderStatus() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            PokemonOrders order = order(APPLICANT_ID, PokemonOrderStatus.ORDERED);
            givenOrderAndRound(order);

            assertThatThrownBy(() -> pokemonService.changeStatus(APPLICANT_LOGIN, ORDER_ID,
                    new PatchPokemonOrderStatusRequest(PokemonOrderStatus.DEPOSIT_CONFIRMED)))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_THE_HOST);
            assertThat(order.getStatus()).isEqualTo(PokemonOrderStatus.ORDERED);
        }

        /** POKEMON-AC-09 */
        @Test
        void theHostCanChangeTheOrderStatus() {
            viewerIs(HOST_LOGIN, HOST_ID);
            PokemonOrders order = order(APPLICANT_ID, PokemonOrderStatus.ORDERED);
            givenOrderAndRound(order);

            pokemonService.changeStatus(HOST_LOGIN, ORDER_ID,
                    new PatchPokemonOrderStatusRequest(PokemonOrderStatus.DEPOSIT_CONFIRMED));

            assertThat(order.getStatus()).isEqualTo(PokemonOrderStatus.DEPOSIT_CONFIRMED);
        }
    }

    /* ===== 댓글 — 지우는 것은 본인·주최자, 고치는 것은 본인만 (POKEMON-AC-13·36) ===== */

    @Nested
    class 댓글 {

        /** POKEMON-AC-13 */
        @Test
        void theAuthorCanDeleteTheirOwnComment() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(orderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(order(APPLICANT_ID, PokemonOrderStatus.ORDERED)));
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            pokemonService.deleteComment(APPLICANT_LOGIN, COMMENT_ID);

            verify(commentRepository).delete(comment);
        }

        /** POKEMON-AC-13 — 주최자는 자기 차수에 달린 댓글을 중재할 수 있다. */
        @Test
        void theHostOfTheRoundCanDeleteSomeoneElsesComment() {
            viewerIs(HOST_LOGIN, HOST_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));
            when(orderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(order(APPLICANT_ID, PokemonOrderStatus.ORDERED)));
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            pokemonService.deleteComment(HOST_LOGIN, COMMENT_ID);

            verify(commentRepository).delete(comment);
        }

        /** POKEMON-AC-13 */
        @Test
        void aStrangerCannotDeleteSomeoneElsesComment() {
            viewerIs(STRANGER_LOGIN, STRANGER_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(APPLICANT_ID)));
            when(orderRepository.findById(ORDER_ID))
                    .thenReturn(Optional.of(order(APPLICANT_ID, PokemonOrderStatus.ORDERED)));
            when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));

            assertThatThrownBy(() -> pokemonService.deleteComment(STRANGER_LOGIN, COMMENT_ID))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            verify(commentRepository, never()).delete(any());
        }

        /** POKEMON-AC-38 — 고친 댓글은 내용이 바뀌고 고친 시각이 박힌다. */
        @Test
        void theAuthorCanEditTheirOwnComment() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            assertThat(comment.getUpdatedAt()).isNull();
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

            PokemonCommentResponse response = pokemonService.updateComment(
                    APPLICANT_LOGIN, COMMENT_ID, new PutPokemonCommentRequest("  고쳐 썼습니다  "));

            assertThat(comment.getContent()).isEqualTo("고쳐 썼습니다");
            assertThat(comment.getUpdatedAt()).isNotNull();
            assertThat(response.content()).isEqualTo("고쳐 썼습니다");
            assertThat(response.updatedAt()).isNotNull();
            assertThat(response.mine()).isTrue();
        }

        /**
         * POKEMON-AC-38 — <b>주최자도 남의 댓글은 고치지 못한다.</b> 지우는 것은 중재지만
         * 고치는 것은 남의 이름으로 다른 말을 남기는 일이다. 삭제 권한을 그대로 가져다 쓰면
         * 이 구분이 조용히 사라진다.
         */
        @Test
        void theHostCannotEditSomeoneElsesComment() {
            viewerIs(HOST_LOGIN, HOST_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

            assertThatThrownBy(() -> pokemonService.updateComment(HOST_LOGIN, COMMENT_ID,
                    new PutPokemonCommentRequest("주최자가 고쳐 쓴 말")))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            assertThat(comment.getContent()).isEqualTo("원래 쓴 댓글");
            assertThat(comment.getUpdatedAt()).isNull();
        }

        /** POKEMON-AC-38 — 전역 운영자도 마찬가지다. 운영자 권한은 삭제까지다. */
        @Test
        void theGlobalManagerCannotEditSomeoneElsesComment() {
            viewerIs(MANAGER_LOGIN, MANAGER_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

            assertThatThrownBy(() -> pokemonService.updateComment(MANAGER_LOGIN, COMMENT_ID,
                    new PutPokemonCommentRequest("운영자가 고쳐 쓴 말")))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
            assertThat(comment.getContent()).isEqualTo("원래 쓴 댓글");
        }

        /** POKEMON-AC-38 — 비회원 댓글은 주인이 없어 아무도 고칠 수 없다. */
        @Test
        void anOwnerlessCommentIsNotEditable() {
            viewerIs(GUEST_LOGIN, null);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment(null)));

            assertThatThrownBy(() -> pokemonService.updateComment(GUEST_LOGIN, COMMENT_ID,
                    new PutPokemonCommentRequest("비회원이 고친 말")))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.NOT_YOURS);
        }

        /** POKEMON-AC-38 — 빈 내용으로 고쳐 댓글을 지우는 우회로를 막는다. */
        @Test
        void anEmptyEditIsRejected() {
            viewerIs(APPLICANT_LOGIN, APPLICANT_ID);
            PokemonOrderComments comment = comment(APPLICANT_ID);
            when(commentRepository.findById(COMMENT_ID)).thenReturn(Optional.of(comment));

            assertThatThrownBy(() -> pokemonService.updateComment(APPLICANT_LOGIN, COMMENT_ID,
                    new PutPokemonCommentRequest("   ")))
                    .isInstanceOf(PokemonException.class)
                    .extracting(e -> ((PokemonException) e).errorCode())
                    .isEqualTo(PokemonErrorCode.EMPTY_COMMENT);
            assertThat(comment.getContent()).isEqualTo("원래 쓴 댓글");
        }
    }

    /* ===== 거들기 ===== */

    private void viewerIs(String loginId, Long memberId) {
        when(memberResolver.optionalMemberId(loginId)).thenReturn(memberId);
    }

    /** 신청서 하나와 그것이 속한 차수를 함께 세운다 — 권한 판정이 둘 다 보기 때문이다. */
    private void givenOrderAndRound(PokemonOrders order) {
        when(orderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(roundRepository.findById(ROUND_ID)).thenReturn(Optional.of(round()));
    }

    private static PokemonRounds round() {
        PokemonRounds round = PokemonRounds.open(HOST_ID, 1, "1차", "INR", PokemonRateMode.FIXED,
                new BigDecimal("14.13"), LocalDateTime.now(), "하나은행 111 주최자", null, null);
        ReflectionTestUtils.setField(round, "id", ROUND_ID);
        return round;
    }

    private static PokemonOrders order(Long memberId, PokemonOrderStatus status) {
        PokemonOrders order = PokemonOrders.create(ROUND_ID, memberId, "신청자", null,
                new BigDecimal("29.00"), BigDecimal.ZERO, new BigDecimal("14.13"),
                LocalDateTime.now(), 410L, 0L, null);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        order.changeStatus(status);
        return order;
    }

    private static PokemonOrderComments comment(Long memberId) {
        PokemonOrderComments comment = PokemonOrderComments.create(
                ORDER_ID, memberId, "작성자", "원래 쓴 댓글");
        ReflectionTestUtils.setField(comment, "id", COMMENT_ID);
        return comment;
    }

    private static PutPokemonRoundRequest roundEdit() {
        return new PutPokemonRoundRequest("고친 제목", "FIXED", new BigDecimal("15.00"),
                "국민은행 222 남", null, null);
    }

    private static PutPokemonOrderRequest orderEdit() {
        return new PutPokemonOrderRequest(null, 0L, BigDecimal.ZERO, null, List.of());
    }
}
