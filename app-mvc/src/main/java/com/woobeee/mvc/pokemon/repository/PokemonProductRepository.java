package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonProductRepository extends JpaRepository<PokemonProducts, Long> {

    /** 신청 화면이 고를 수 있는 상품 — 그 차수 주최자의, 그 통화의, 내려가지 않은 것. */
    List<PokemonProducts> findAllByHostMemberIdAndCurrencyAndActiveTrueOrderBySortOrderAsc(
            Long hostMemberId, String currency);

    /** 신청서에 담긴 상품을 한 번에 읽는다 — 항목마다 조회하면 N+1 이다. */
    List<PokemonProducts> findAllByIdIn(Collection<Long> ids);

    /** 관리 화면은 자기 상품표를 통화 구분 없이 전부 본다(내려간 것 포함). */
    List<PokemonProducts> findAllByHostMemberIdOrderBySortOrderAsc(Long hostMemberId);

    boolean existsByHostMemberIdAndNameAndCurrency(Long hostMemberId, String name, String currency);
}
