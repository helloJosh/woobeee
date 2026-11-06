package com.woobeee.mvc.ai;

import com.woobeee.mvc.ai.entity.AiActionStatus;
import com.woobeee.mvc.ai.entity.AiOutboxStatus;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.ai.repository.AiActionRepository;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import com.woobeee.mvc.ai.service.AiRequestService;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.entity.MemberRole;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.entity.PokemonHosts;
import com.woobeee.mvc.pokemon.entity.PokemonRounds;
import com.woobeee.mvc.pokemon.repository.PokemonHostRepository;
import com.woobeee.mvc.pokemon.repository.PokemonProductRepository;
import com.woobeee.mvc.pokemon.repository.PokemonRoundRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 단위 테스트가 증명하지 못하는 것 — <b>실 DB 에서의 트랜잭션 롤백과 동시성 제약</b> — 을 market_test 에 대고 확인한다.
 *
 * <p>테스트 트랜잭션으로 감싸지 않는다: 서비스가 던진 예외가 서비스의 트랜잭션을 되돌리는지가 핵심이라, 감싸면 그
 * 되돌림이 테스트 트랜잭션에 삼켜져 아무것도 증명하지 못한다. 대신 만든 행의 id 를 모아 두었다가 끝나고 그것만 지운다.
 * 시작 전에 DB 이름을 확인해, 설정이 틀려 공유 DB(market)에 붙었다면 아무것도 쓰지 않고 실패한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AiPipelineIntegrationTest {

    @Autowired AiRequestService service;
    @Autowired AiRequestRepository requests;
    @Autowired AiOutboxRepository outbox;
    @Autowired AiActionRepository actions;
    @Autowired MemberRepository members;
    @Autowired PokemonHostRepository hosts;
    @Autowired PokemonRoundRepository rounds;
    @Autowired PokemonProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    private final List<Long> memberIds = new ArrayList<>();
    private final List<Long> roundIds = new ArrayList<>();
    private final List<UUID> requestIds = new ArrayList<>();

    @BeforeEach
    void onlyEverTouchTheTestDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("이 테스트는 행을 쓰고 지운다 — market_test 에서만 돌아야 한다").isEqualTo("market_test");
    }

    @AfterEach
    void cleanUpOnlyWhatThisTestCreated() {
        for (UUID id : requestIds) {
            jdbc.update("DELETE FROM ai_requests WHERE id = ?", id);
        }
        for (Long roundId : roundIds) {
            jdbc.update("DELETE FROM pokemon_products WHERE round_id = ?", roundId);
            jdbc.update("DELETE FROM pokemon_rounds WHERE id = ?", roundId);
        }
        for (Long memberId : memberIds) {
            jdbc.update("DELETE FROM ai_requests WHERE member_id = ?", memberId);
            jdbc.update("DELETE FROM pokemon_hosts WHERE member_id = ?", memberId);
            jdbc.update("DELETE FROM members WHERE id = ?", memberId);
        }
    }

    private Member member(String label) {
        String unique = "__test_ai__" + label + "_" + UUID.randomUUID();
        Member saved = members.save(Member.builder().googleSubject(unique).email(unique + "@woobeee.test")
                .nickname(label).termsAgreed(true).privacyPolicyAgreed(true).role(MemberRole.ROLE_MEMBER)
                .active(true).createdAt(LocalDateTime.now()).build());
        memberIds.add(saved.getId());
        return saved;
    }

    private PokemonRounds roundOf(Member host) {
        hosts.save(PokemonHosts.claim(host.getId(), "ai-" + UUID.randomUUID().toString().substring(0, 8)));
        PokemonRounds round = rounds.save(PokemonRounds.open(host.getId(), 1, "1차", "INR", "신한 110-000", null, null));
        roundIds.add(round.getId());
        return round;
    }

    /** DRAFTED 요청과 상품 제안들을 만든다. */
    private UUID draftedWithProductProposals(Member owner, PokemonRounds round, String... names) {
        AiRequests request = requests.save(AiRequests.receive(owner.getId(), "상품 추가", List.of()));
        requestIds.add(request.getId());
        jdbc.update("UPDATE ai_requests SET status = 'DRAFTED', attempts = 1 WHERE id = ?", request.getId());
        for (String name : names) {
            // @Modifying 쿼리는 트랜잭션이 필요하다 — 운영에서는 서비스 트랜잭션이 감싸지만 여기서는 직접 감싼다
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    actions.insertIfAbsent(request.getId(), "RP:" + round.getId() + ":" + name, "ROUND_PRODUCTS",
                            "{\"roundId\":" + round.getId() + ",\"name\":\"" + name + "\",\"price\":29.00,\"coins\":0}",
                            LocalDateTime.now()));
        }
        return request.getId();
    }

    private long actionId(UUID requestId, int index) {
        return actions.findByRequestIdOrderById(requestId).get(index).getId();
    }

    private String actionStatus(UUID requestId, int index) {
        return actions.findByRequestIdOrderById(requestId).get(index).getStatus().name();
    }

    private String requestStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM ai_requests WHERE id = ?", String.class, id);
    }

    @Test
    void AI_AC_12_aRejectedRegistrationRollsTheApprovalBackSoTheProposalStaysPending() {
        Member host = member("host");
        Member stranger = member("stranger");
        // 전역 운영자 명단(기본 1)에 걸리면 이 시나리오가 성립하지 않는다 — 테스트 DB 의 id 가 1 이면 건너뛴다
        Assumptions.assumeFalse(stranger.getId() == 1L, "member id 1 is a global manager");
        PokemonRounds hostsRound = roundOf(host);
        UUID request = draftedWithProductProposals(stranger, hostsRound, "GO패스 디럭스");

        // 주최자가 아닌 사람의 승인 → 기존 서비스가 NOT_THE_HOST → ai_applyForbidden
        assertThatThrownBy(() -> service.apply(stranger.getEmail(), request, actionId(request, 0)))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.APPLY_FORBIDDEN));

        // 승인 표시(CAS)까지 되돌아갔어야 한다 — 되돌아가지 않았다면 PENDING 이 아니라 APPLIED 로 남았을 것이다
        assertThat(actionStatus(request, 0)).isEqualTo(AiActionStatus.PENDING.name());
        assertThat(requestStatus(request)).isEqualTo(AiRequestStatus.DRAFTED.name());
        assertThat(products.findAllByRoundIdOrderBySortOrderAsc(hostsRound.getId())).isEmpty();
    }

    @Test
    void AI_AC_11_aSecondApplyOfTheSameProposalRegistersExactlyOneProduct() {
        Member host = member("host");
        PokemonRounds round = roundOf(host);
        // 제안이 둘이라 첫 승인 뒤에도 요청은 DRAFTED 로 남는다
        UUID request = draftedWithProductProposals(host, round, "GO패스 디럭스", "GO패스 프리미엄");
        long first = actionId(request, 0);

        service.apply(host.getEmail(), request, first);

        assertThat(products.findAllByRoundIdOrderBySortOrderAsc(round.getId())).hasSize(1);
        assertThat(products.findAllByRoundIdOrderBySortOrderAsc(round.getId()).get(0).getName()).isEqualTo("GO패스 디럭스");
        assertThat(actionStatus(request, 0)).isEqualTo(AiActionStatus.APPLIED.name());
        assertThat(requestStatus(request)).isEqualTo(AiRequestStatus.DRAFTED.name());

        assertThatThrownBy(() -> service.apply(host.getEmail(), request, first))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.ACTION_ALREADY_DONE));
        assertThat(products.findAllByRoundIdOrderBySortOrderAsc(round.getId())).hasSize(1);
    }

    @Test
    void AI_AC_11_decidingEveryProposalSettlesTheRequestAndALateRepeatIsStillAlreadyDone() {
        Member host = member("host");
        PokemonRounds round = roundOf(host);
        UUID request = draftedWithProductProposals(host, round, "GO패스 디럭스", "GO패스 프리미엄");

        service.apply(host.getEmail(), request, actionId(request, 0));
        service.reject(host.getEmail(), request, actionId(request, 1));

        assertThat(requestStatus(request)).isEqualTo(AiRequestStatus.APPLIED.name());
        assertThat(products.findAllByRoundIdOrderBySortOrderAsc(round.getId())).hasSize(1);
        assertThatThrownBy(() -> service.apply(host.getEmail(), request, actionId(request, 0)))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.ACTION_ALREADY_DONE));
    }

    @Test
    void AI_AC_03_intakeSucceedsWithoutTheBrokerAndLeavesAPendingOutboxRow() {
        Member me = member("me");

        var response = service.create(me.getEmail(), "상품 목록을 정리해줘", List.of());
        requestIds.add(response.id());

        assertThat(response.status()).isEqualTo("RECEIVED");
        assertThat(outbox.findAll()).filteredOn(row -> row.getRequestId().equals(response.id()))
                .singleElement().satisfies(row -> assertThat(row.getStatus()).isEqualTo(AiOutboxStatus.PENDING));
    }

    @Test
    void AI_AC_19_aSecondSubmitWhileOneIsInFlightIsRefusedAndTheDatabaseBacksItUp() {
        Member me = member("me");
        var first = service.create(me.getEmail(), "첫 요청", List.of());
        requestIds.add(first.id());

        assertThatThrownBy(() -> service.create(me.getEmail(), "두 번째", List.of()))
                .isInstanceOfSatisfying(AiException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.REQUEST_IN_PROGRESS));

        // 서비스 사전 검사를 우회해도 DB 가 막는다
        assertThatThrownBy(() -> requests.saveAndFlush(AiRequests.receive(me.getId(), "우회", List.of())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(requests.findFirstByMemberIdOrderByCreatedAtDesc(me.getId())).get()
                .extracting(AiRequests::getId).isEqualTo(first.id());
    }
}
