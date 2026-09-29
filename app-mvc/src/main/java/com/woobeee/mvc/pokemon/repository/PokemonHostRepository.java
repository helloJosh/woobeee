package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonHosts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PokemonHostRepository extends JpaRepository<PokemonHosts, Long> {

    Optional<PokemonHosts> findByHandle(String handle);

    boolean existsByHandle(String handle);

    /** 차수 목록에 주최자 이름을 붙일 때 한 번에 읽는다. */
    List<PokemonHosts> findAllByMemberIdIn(Collection<Long> memberIds);
}
