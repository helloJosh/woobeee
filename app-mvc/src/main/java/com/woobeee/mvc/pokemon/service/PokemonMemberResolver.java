package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.pokemon.PokemonProperties;
import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * ScheduleMemberResolver 와 같은 역할이지만 pokemon 은 <b>비회원 신청을 허용</b>하므로
 * "로그인이 없으면 예외" 가 기본이 아니다. 로그인 여부는 {@link #optionalMember} 로 묻고,
 * 진행 상태를 바꾸는 경로만 {@link #requireManager} 를 쓴다.
 *
 * <p>운영자는 블로그의 ROLE_ADMIN 이 아니라 {@link PokemonProperties#managerMemberIds}
 * 명단이다 — 이 공동구매를 실제로 굴리는 사람만 상태를 옮겨야 하기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class PokemonMemberResolver {
    private final MemberRepository memberRepository;
    private final PokemonProperties properties;

    /**
     * loginId 헤더는 AccessTokenLoginIdHeaderFilter 가 유효한 토큰에서만 주입한다.
     * 헤더가 없으면 비회원이고, 있는데 회원이 없으면 계정이 지워진 토큰이다.
     */
    public Optional<Member> optionalMember(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return Optional.empty();
        }
        return Optional.of(memberRepository.findByEmail(loginId)
                .orElseThrow(PokemonErrorCode.MEMBER_NOT_FOUND::asException));
    }

    public Long optionalMemberId(String loginId) {
        return optionalMember(loginId).map(Member::getId).orElse(null);
    }

    public Member requireManager(String loginId) {
        Member member = optionalMember(loginId)
                .orElseThrow(PokemonErrorCode.MANAGER_REQUIRED::asException);
        if (!properties.isManager(member.getId())) {
            throw PokemonErrorCode.MANAGER_REQUIRED.asException();
        }
        return member;
    }

    public boolean isManager(String loginId) {
        return properties.isManager(optionalMemberId(loginId));
    }
}
