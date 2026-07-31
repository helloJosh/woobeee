package com.woobeee.mvc.auth.repository;

import java.util.Optional;

import com.woobeee.mvc.auth.entity.Seller;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerRepository extends JpaRepository<Seller, Long> {
    boolean existsByGoogleSubject(String googleSubject);

    Optional<Seller> findByGoogleSubject(String googleSubject);

    Optional<Seller> findByEmail(String email);
}
