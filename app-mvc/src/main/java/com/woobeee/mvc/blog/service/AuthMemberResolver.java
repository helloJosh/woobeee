package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.exception.CustomNotFoundException;
import com.woobeee.mvc.blog.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AuthMemberResolver {
    private final MemberRepository memberRepository;

    public MemberIdentity requireByLoginId(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            throw new CustomAuthenticationException(ErrorCode.comment_needAuthentication);
        }

        return findByLoginId(loginId)
                .orElseThrow(() -> new CustomNotFoundException(ErrorCode.login_userNotFound));
    }

    public Optional<MemberIdentity> findByLoginId(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            return Optional.empty();
        }

        return memberRepository.findByEmail(loginId)
                .map(member -> new MemberIdentity(member.getId(), member.getEmail()));
    }

    public String resolveLoginId(Long memberId) {
        if (memberId == null) {
            throw new CustomNotFoundException(ErrorCode.login_userNotFound);
        }

        return memberRepository.findById(memberId)
                .map(Member::getEmail)
                .orElseThrow(() -> new CustomNotFoundException(ErrorCode.login_userNotFound));
    }

    public record MemberIdentity(
            Long memberId,
            String loginId
    ) {
    }
}
