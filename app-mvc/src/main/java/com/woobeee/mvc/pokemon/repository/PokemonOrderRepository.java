package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonOrderRepository extends JpaRepository<PokemonOrders, Long> {

    /** 한 차수의 신청서. 몇십 건 규모라 페이징을 두지 않았다. */
    List<PokemonOrders> findAllByRoundIdOrderByCreatedAtDesc(Long roundId);

    /** 차수 목록에 건수·금액을 붙일 때 한 번에 읽는다 — 차수마다 조회하면 N+1 이다. */
    List<PokemonOrders> findAllByRoundIdIn(Collection<Long> roundIds);
}
