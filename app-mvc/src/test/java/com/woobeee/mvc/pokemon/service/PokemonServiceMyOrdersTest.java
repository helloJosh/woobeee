package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.pokemon.api.response.PokemonMyOrderResponse;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import com.woobeee.mvc.pokemon.rate.ExchangeRate;
import com.woobeee.mvc.pokemon.rate.ExchangeRateService;
import com.woobeee.mvc.pokemon.repository.PokemonMyOrderRow;
import com.woobeee.mvc.pokemon.repository.PokemonOrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** POKEMON-AC-36 — 마이페이지 목록은 로그인 회원 본인 것만, 로그인 없이는 401. */
@ExtendWith(MockitoExtension.class)
class PokemonServiceMyOrdersTest {

    @Mock PokemonOrderRepository orderRepository;
    @Mock PokemonMemberResolver memberResolver;
    @Mock ExchangeRateService exchangeRateService;
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
        when(row.getTotalAmount()).thenReturn(new BigDecimal("298.00"));
        when(row.getDonationKrw()).thenReturn(1_000L);
        when(exchangeRateService.current("INR")).thenReturn(
                new ExchangeRate("INR", new BigDecimal("14.13"), LocalDateTime.of(2026, 11, 6, 9, 10), false));
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 12, 0);
        when(row.getCreatedAt()).thenReturn(at);
        when(orderRepository.findMyOrders(42L)).thenReturn(List.of(row));

        List<PokemonMyOrderResponse> result = service.getMyOrders("me@woobeee.com");

        assertThat(result).containsExactly(new PokemonMyOrderResponse(
                7L, 3L, "hs", 2, "2차", "INR", "OPEN", "DEPOSIT_CONFIRMED", 5_211L, at));
    }

    /** POKEMON-AC-36 — 같은 통화의 신청서가 여럿이어도 환율은 통화마다 한 번만 읽는다. */
    @Test
    void POKEMON_AC_36_readsTheRateOncePerCurrency() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(42L);
        when(memberResolver.optionalMember("me@woobeee.com")).thenReturn(Optional.of(member));
        when(exchangeRateService.current("INR")).thenReturn(
                new ExchangeRate("INR", new BigDecimal("14.00"), LocalDateTime.of(2026, 11, 6, 9, 10), false));
        PokemonMyOrderRow first = row(1L, "100.00", 0L);
        PokemonMyOrderRow second = row(2L, "200.00", 500L);
        when(orderRepository.findMyOrders(42L)).thenReturn(List.of(first, second));

        List<PokemonMyOrderResponse> result = service.getMyOrders("me@woobeee.com");

        assertThat(result).extracting(PokemonMyOrderResponse::estimatedKrw).containsExactly(1_400L, 3_300L);
        verify(exchangeRateService, times(1)).current("INR");
    }

    private static PokemonMyOrderRow row(long orderId, String totalInr, long donationKrw) {
        PokemonMyOrderRow row = mock(PokemonMyOrderRow.class);
        when(row.getOrderId()).thenReturn(orderId);
        when(row.getCurrency()).thenReturn("INR");
        when(row.getTotalAmount()).thenReturn(new BigDecimal(totalInr));
        when(row.getDonationKrw()).thenReturn(donationKrw);
        return row;
    }
}
