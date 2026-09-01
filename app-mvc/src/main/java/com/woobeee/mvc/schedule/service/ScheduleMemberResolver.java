package com.woobeee.mvc.schedule.service;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.schedule.exception.ScheduleErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * blog 의 AuthMemberResolver 와 같은 역할이지만 schedule 의 예외를 던진다 —
 * blog 도메인 예외에 의존을 만들지 않기 위해 따로 둔다 (auth 의 MemberRepository 의존은
 * blog 와 같은 방향이라 허용).
 */
@Component
@RequiredArgsConstructor
public class ScheduleMemberResolver {
    private final MemberRepository memberRepository;

    public Long requireMemberId(String loginId) {
        return requireMember(loginId).getId();
    }

    /** 알림 설정처럼 Member 엔티티 자체가 필요한 경로용. 트랜잭션 안에서는 관리 엔티티다. */
    public Member requireMember(String loginId) {
        if (!StringUtils.hasText(loginId)) {
            throw ScheduleErrorCode.UNAUTHORIZED.asException();
        }
        return memberRepository.findByEmail(loginId)
                .orElseThrow(ScheduleErrorCode.MEMBER_NOT_FOUND::asException);
    }
}
