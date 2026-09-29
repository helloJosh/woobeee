package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrderItems;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PokemonOrderItemRepository extends JpaRepository<PokemonOrderItems, Long> {

    /**
     * 목록 화면의 항목은 이 한 번의 IN 조회로 모두 가져와 메모리에서 주문별로 묶는다.
     * 주문을 돌면서 건별로 조회하면 N+1 이다.
     */
    List<PokemonOrderItems> findAllByOrderIdIn(Collection<Long> orderIds);

    void deleteAllByOrderId(Long orderId);
}
