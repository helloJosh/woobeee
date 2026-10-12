package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.pokemon.api.response.PokemonMyOrderResponse;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import com.woobeee.mvc.pokemon.repository.PokemonMyOrderRow;
import com.woobeee.mvc.pokemon.repository.PokemonOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** POKEMON-AC-36 — 마이페이지 목록은 로그인 회원 본인 것만, 로그인 없이는 401. */
@ExtendWith(MockitoExtension.class)
class PokemonServiceMyOrdersTest {

    @Mock PokemonOrderRepository orderRepository;
    @Mock PokemonMemberResolver memberResolver;
    @InjectMocks PokemonServiceImpl service;

    @Test
    void POKEMON_AC_36_requiresLogin() {
        when(memberResolver.optionalMember(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMyOrders(null))
                .isInstanceOf(PokemonException.class)
                .extracting(e -> ((PokemonException) e).errorCode())
                .isEqualTo(PokemonErrorCode.LOGIN_REQUIRED);
        verifyNoInteractions(orderRepository);
    }

    @Test
    void POKEMON_AC_36_mapsRowsOfTheLoggedInMember() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(42L);
        when(memberResolver.optionalMember("me@woobeee.com")).thenReturn(Optional.of(member));

        PokemonMyOrderRow row = mock(PokemonMyOrderRow.class);
        when(row.getOrderId()).thenReturn(7L);
        when(row.getRoundId()).thenReturn(3L);
        when(row.getHostHandle()).thenReturn("hs");
        when(row.getRoundSequence()).thenReturn(2);
        when(row.getRoundTitle()).thenReturn("2차");
        when(row.getCurrency()).thenReturn("INR");
        when(row.getRoundStatus()).thenReturn("OPEN");
        when(row.getOrderStatus()).thenReturn("DEPOSIT_CONFIRMED");
        when(row.getTransferKrw()).thenReturn(12_345L);
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 12, 0);
        when(row.getCreatedAt()).thenReturn(at);
        when(orderRepository.findMyOrders(42L)).thenReturn(List.of(row));

        List<PokemonMyOrderResponse> result = service.getMyOrders("me@woobeee.com");

        assertThat(result).containsExactly(new PokemonMyOrderResponse(
                7L, 3L, "hs", 2, "2차", "INR", "OPEN", "DEPOSIT_CONFIRMED", 12_345L, at));
    }
}
