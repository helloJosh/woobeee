package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PokemonOrderRepository extends JpaRepository<PokemonOrders, Long> {

    /** 진행도 페이지는 전체를 최신순으로 한 번에 읽는다 — 친구 몇십 건 규모라 페이징을 두지 않았다. */
    List<PokemonOrders> findAllByOrderByCreatedAtDesc();
}
