package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonHosts;
import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import com.woobeee.mvc.pokemon.entity.PokemonRounds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POKEMON-AC-36 — 마이페이지의 "내가 참가한 공동구매" 쿼리를 실 Postgres 에 대고 고정한다.
 * 개발 DB 에 다른 신청서가 있어도 흔들리지 않도록 테스트 전용 회원 id 만 조회하고, 트랜잭션은 롤백된다.
 */
@SpringJUnitConfig(initializers = ConfigDataApplicationContextInitializer.class)
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.pokemon.repository")
@Transactional
@ActiveProfiles("test")
class PokemonOrderRepositoryTest {

    private static final long HOST_A = 9_100_001L;
    private static final long HOST_B = 9_100_002L;
    private static final long ME = 9_100_003L;
    private static final long SOMEONE_ELSE = 9_100_004L;

    @Autowired PokemonHostRepository hostRepository;
    @Autowired PokemonRoundRepository roundRepository;
    @Autowired PokemonOrderRepository orderRepository;

    private PokemonRounds round(long hostMemberId, int sequence, String currency) {
        return roundRepository.saveAndFlush(PokemonRounds.open(
                hostMemberId, sequence, sequence + "차", currency, "신한 110-000", null, null));
    }

    private PokemonOrders order(long roundId, Long memberId, long donationKrw) {
        return orderRepository.saveAndFlush(PokemonOrders.create(
                roundId, memberId, "신청자", null, new BigDecimal("100"), BigDecimal.ZERO,
                donationKrw, null));
    }

    @Test
    void POKEMON_AC_36_returnsOnlyMyOrdersWithRoundAndHostHandle() {
        hostRepository.saveAndFlush(PokemonHosts.claim(HOST_A, "test-host-a"));
        hostRepository.saveAndFlush(PokemonHosts.claim(HOST_B, "test-host-b"));
        PokemonRounds a1 = round(HOST_A, 1, "INR");
        PokemonRounds b2 = round(HOST_B, 2, "USD");

        PokemonOrders mineFirst = order(a1.getId(), ME, 1_650);
        PokemonOrders mineSecond = order(b2.getId(), ME, 2_000);
        order(a1.getId(), SOMEONE_ELSE, 9_999);
        order(a1.getId(), null, 7_777);

        List<PokemonMyOrderRow> rows = orderRepository.findMyOrders(ME);

        // 남의 신청서·비회원 신청서는 섞이지 않고, 최신순이다.
        assertThat(rows).extracting(PokemonMyOrderRow::getOrderId)
                .containsExactly(mineSecond.getId(), mineFirst.getId());

        PokemonMyOrderRow latest = rows.get(0);
        assertThat(latest.getRoundId()).isEqualTo(b2.getId());
        assertThat(latest.getHostHandle()).isEqualTo("test-host-b");
        assertThat(latest.getRoundSequence()).isEqualTo(2);
        assertThat(latest.getCurrency()).isEqualTo("USD");
        assertThat(latest.getRoundStatus()).isEqualTo("OPEN");
        assertThat(latest.getOrderStatus()).isEqualTo("ORDERED");
        assertThat(latest.getTotalAmount()).isEqualByComparingTo("100");
        assertThat(latest.getDonationKrw()).isEqualTo(2_000L);
        assertThat(latest.getCreatedAt()).isNotNull();
    }

    @Test
    void POKEMON_AC_36_memberWithoutOrdersGetsAnEmptyList() {
        assertThat(orderRepository.findMyOrders(ME)).isEmpty();
    }
}
