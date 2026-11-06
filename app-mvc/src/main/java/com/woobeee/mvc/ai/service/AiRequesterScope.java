package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.exception.AiErrorCode;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.api.response.PokemonHomeResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonHostResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonManagedProductResponse;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import com.woobeee.mvc.pokemon.service.PokemonService;
import com.woobeee.mvc.schedule.api.response.GetScheduleTreeResponse;
import com.woobeee.mvc.schedule.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 도구가 <b>요청자의 범위로만</b> 읽게 하는 문(AI-AC-14). 모든 읽기는 요청이 가진 회원의 이메일로 기존
 * 서비스를 부르고, 다른 회원을 가리킬 인자가 없다. pokemon·schedule 에 대한 ai 의 의존은 이 클래스와
 * {@link AiActionApplier} 두 곳으로 한정한다.
 */
@Component
@RequiredArgsConstructor
public class AiRequesterScope {
    private final MemberRepository memberRepository;
    private final PokemonService pokemonService;
    private final ScheduleService scheduleService;

    public String emailOf(Long memberId) {
        return memberRepository.findById(memberId)
                .map(member -> member.getEmail())
                .orElseThrow(AiErrorCode.LOGIN_REQUIRED::asException);
    }

    /** 내가 연 차수들. 아직 주소를 정하지 않았으면 빈 주최자다. */
    public PokemonHostResponse myRounds(String email) {
        PokemonHomeResponse home = pokemonService.getHome(email);
        if (home.myHandle() == null) {
            return new PokemonHostResponse(null, null, true, null, List.of());
        }
        return pokemonService.getHost(email, home.myHandle());
    }

    /** 내 차수의 상품표. 남의 차수이거나 없는 차수면 같은 {@code ai_applyForbidden} 이다 — 존재를 가르쳐 주지 않는다. */
    public List<PokemonManagedProductResponse> roundProducts(String email, Long roundId) {
        try {
            return pokemonService.getRoundProducts(email, roundId);
        } catch (PokemonException ex) {
            throw AiErrorCode.APPLY_FORBIDDEN.asException();
        }
    }

    public GetScheduleTreeResponse scheduleTree(String email) {
        return scheduleService.getTree(email);
    }
}
