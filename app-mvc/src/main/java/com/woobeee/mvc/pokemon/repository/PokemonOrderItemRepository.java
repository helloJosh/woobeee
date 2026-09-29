package com.woobeee.mvc.pokemon.repository;

import com.woobeee.mvc.pokemon.entity.PokemonOrderItems;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PokemonOrderItemRepository extends JpaRepository<PokemonOrderItems, Long> {

    /**
     * 목록 화면의 항목은 이 한 번의 IN 조회로 모두 가져와 메모리에서 주문별로 묶는다.
     * 주문을 돌면서 건별로 조회하면 N+1 이다.
     */
    List<PokemonOrderItems> findAllByOrderIdIn(Collection<Long> orderIds);

    void deleteAllByOrderId(Long orderId);

    /**
     * 신청서에 한 번이라도 쓰인 상품 id. 관리 화면이 "지울 수 있는 상품" 을 가리는 데 쓴다 —
     * 쓰인 상품은 지우는 대신 내린다. 상품마다 세면 N+1 이라 한 번에 모아 온다.
     */
    @Query(value = "SELECT DISTINCT product_id FROM pokemon_order_items WHERE product_id IS NOT NULL",
            nativeQuery = true)
    List<Long> findUsedProductIds();
}
