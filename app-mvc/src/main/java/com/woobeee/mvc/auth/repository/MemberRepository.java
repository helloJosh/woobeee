package com.woobeee.mvc.auth.repository;

import java.util.Optional;

import com.woobeee.mvc.auth.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {
    boolean existsByGoogleSubject(String googleSubject);

    Optional<Member> findByGoogleSubject(String googleSubject);

    Optional<Member> findByEmail(String email);
}
