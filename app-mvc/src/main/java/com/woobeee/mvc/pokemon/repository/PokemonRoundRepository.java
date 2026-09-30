package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonRounds;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PokemonRoundRepository extends JpaRepository<PokemonRounds, Long> {

    /** 한 주최자의 차수 목록 — 최신 차수가 위로. */
    List<PokemonRounds> findAllByHostMemberIdOrderBySequenceDesc(Long hostMemberId);

    Optional<PokemonRounds> findByHostMemberIdAndSequence(Long hostMemberId, int sequence);

    /** 다음 차수 번호를 정할 때 쓴다. */
    Optional<PokemonRounds> findFirstByHostMemberIdOrderBySequenceDesc(Long hostMemberId);
}
