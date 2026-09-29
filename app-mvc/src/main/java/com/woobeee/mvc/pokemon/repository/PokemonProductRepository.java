package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonProductRepository extends JpaRepository<PokemonProducts, Long> {

    /** 신청 화면이 고를 수 있는 상품. 내려간 것은 빼고 정렬된 순서로. */
    List<PokemonProducts> findAllByActiveTrueOrderBySortOrderAsc();

    /** 신청서에 담긴 상품을 한 번에 읽는다 — 항목마다 조회하면 N+1 이다. */
    List<PokemonProducts> findAllByIdIn(Collection<Long> ids);
}
