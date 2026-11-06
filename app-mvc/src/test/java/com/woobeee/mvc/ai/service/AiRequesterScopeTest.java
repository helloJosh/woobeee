package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.ai.exception.AiException;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.service.PokemonService;
import com.woobeee.mvc.schedule.service.ScheduleService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AI-AC-14 — 요청자 범위로만 읽는다. 도메인 예외는 ai 에러 코드로 옮긴다. */
class AiRequesterScopeTest {

    private final MemberRepository members = mock(MemberRepository.class);
    private final PokemonService pokemon = mock(PokemonService.class);
    private final ScheduleService schedule = mock(ScheduleService.class);
    private final AiRequesterScope scope = new AiRequesterScope(members, pokemon, schedule);

    @Test
    void AI_AC_14_emailOf_unknownMemberIsLoginRequired() {
        when(members.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> scope.emailOf(1L)).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.LOGIN_REQUIRED));
    }

    @Test
    void AI_AC_14_emailOf_returnsTheMembersEmail() {
        Member member = mock(Member.class);
        when(member.getEmail()).thenReturn("me@woobeee.com");
        when(members.findById(1L)).thenReturn(Optional.of(member));

        assertThat(scope.emailOf(1L)).isEqualTo("me@woobeee.com");
    }

    @Test
    void AI_AC_14_roundProducts_notTheHostBecomesApplyForbidden() {
        when(pokemon.getRoundProducts("me@woobeee.com", 9L)).thenThrow(PokemonErrorCode.NOT_THE_HOST.asException());

        assertThatThrownBy(() -> scope.roundProducts("me@woobeee.com", 9L)).isInstanceOfSatisfying(AiException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(AiErrorCode.APPLY_FORBIDDEN));
    }

    @Test
    void AI_AC_14_myRounds_withoutAHandleIsAnEmptyHost() {
        when(pokemon.getHome("me@woobeee.com")).thenReturn(
                new com.woobeee.mvc.pokemon.api.response.PokemonHomeResponse(null, null, true, java.util.List.of()));

        assertThat(scope.myRounds("me@woobeee.com").rounds()).isEmpty();
    }
}
