package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonProductRepository extends JpaRepository<PokemonProducts, Long> {

    /** 신청 화면이 고를 수 있는 상품 — 그 차수의, 내려가지 않은 것. */
    List<PokemonProducts> findAllByRoundIdAndActiveTrueOrderBySortOrderAsc(Long roundId);

    /** 차수 상품 관리 화면 — 내려간 것까지. */
    List<PokemonProducts> findAllByRoundIdOrderBySortOrderAsc(Long roundId);

    /** 차수를 열 때 복사할 틀. 그 통화의 것만 가져온다. */
    List<PokemonProducts> findAllByHostMemberIdAndCurrencyAndRoundIdIsNullOrderBySortOrderAsc(
            Long hostMemberId, String currency);

    /** 신청서에 담긴 상품을 한 번에 읽는다 — 항목마다 조회하면 N+1 이다. */
    List<PokemonProducts> findAllByIdIn(Collection<Long> ids);

    /** 내 상품표(틀) — 통화 구분 없이 전부, 내려간 것 포함. */
    List<PokemonProducts> findAllByHostMemberIdAndRoundIdIsNullOrderBySortOrderAsc(Long hostMemberId);

    boolean existsByHostMemberIdAndNameAndCurrencyAndRoundIdIsNull(
            Long hostMemberId, String name, String currency);

    boolean existsByRoundIdAndNameAndCurrency(Long roundId, String name, String currency);
}
