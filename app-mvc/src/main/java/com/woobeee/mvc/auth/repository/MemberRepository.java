package com.woobeee.mvc.auth.repository;

import java.util.List;
import java.util.Optional;

import com.woobeee.mvc.auth.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByGoogleSubject(String googleSubject);

    Optional<Member> findByGoogleSubject(String googleSubject);

    Optional<Member> findByEmail(String email);

    /** 일정 Slack 다이제스트 대상 — webhook 을 등록한 멤버만. */
    List<Member> findAllBySlackWebhookUrlNotNull();
}
