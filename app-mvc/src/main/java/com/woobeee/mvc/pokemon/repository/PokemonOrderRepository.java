package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PokemonOrderRepository extends JpaRepository<PokemonOrders, Long> {

    /** 한 차수의 신청서. 몇십 건 규모라 페이징을 두지 않았다. */
    List<PokemonOrders> findAllByRoundIdOrderByCreatedAtDesc(Long roundId);

    /** 차수 목록에 건수·금액을 붙일 때 한 번에 읽는다 — 차수마다 조회하면 N+1 이다. */
    List<PokemonOrders> findAllByRoundIdIn(Collection<Long> roundIds);

    /**
     * 한 회원이 낸 신청서에 차수·주최자 주소를 붙여 최신순으로. 조인 한 번이라 신청서마다
     * 차수를 다시 읽지 않는다(N+1 없음). 같은 시각이면 id 로 순서를 고정한다.
     */
    @Query(value = """
            SELECT o.id            AS "orderId",
                   o.round_id      AS "roundId",
                   h.handle        AS "hostHandle",
                   r.sequence      AS "roundSequence",
                   r.title         AS "roundTitle",
                   r.currency      AS "currency",
                   r.status        AS "roundStatus",
                   o.status        AS "orderStatus",
                   o.total_amount  AS "totalAmount",
                   o.donation_krw  AS "donationKrw",
                   o.created_at    AS "createdAt"
              FROM pokemon_orders o
              JOIN pokemon_rounds r ON r.id = o.round_id
              JOIN pokemon_hosts h  ON h.member_id = r.host_member_id
             WHERE o.member_id = :memberId
             ORDER BY o.created_at DESC, o.id DESC
            """, nativeQuery = true)
    List<PokemonMyOrderRow> findMyOrders(@Param("memberId") Long memberId);
}
