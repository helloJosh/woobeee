package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrderComments;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonOrderCommentRepository extends JpaRepository<PokemonOrderComments, Long> {

    /** 목록 화면의 댓글은 이 한 번의 IN 조회로 모두 가져와 신청서별로 묶는다. */
    List<PokemonOrderComments> findAllByOrderIdInOrderByCreatedAtAsc(Collection<Long> orderIds);

    void deleteAllByOrderId(Long orderId);
}
