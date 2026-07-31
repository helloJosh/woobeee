package com.woobeee.mvc.auth.repository;

import java.util.Optional;

import com.woobeee.mvc.auth.entity.Buyer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BuyerRepository extends JpaRepository<Buyer, Long> {
    boolean existsByGoogleSubject(String googleSubject);

    Optional<Buyer> findByGoogleSubject(String googleSubject);

    Optional<Buyer> findByEmail(String email);
}
