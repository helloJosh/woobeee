package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.auth.entity.Buyer;
import com.woobeee.mvc.auth.entity.Seller;
import com.woobeee.mvc.auth.repository.BuyerRepository;
import com.woobeee.mvc.auth.repository.SellerRepository;
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
    public static final String ROLE_BUYER = "ROLE_BUYER";
    public static final String ROLE_SELLER = "ROLE_SELLER";

    private final BuyerRepository buyerRepository;
    private final SellerRepository sellerRepository;

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

        Optional<Buyer> buyer = buyerRepository.findByEmail(loginId);
        if (buyer.isPresent()) {
            return Optional.of(new MemberIdentity(
                    buyer.get().getId(),
                    ROLE_BUYER,
                    buyer.get().getEmail()
            ));
        }

        Optional<Seller> seller = sellerRepository.findByEmail(loginId);
        if (seller.isPresent()) {
            return Optional.of(new MemberIdentity(
                    seller.get().getId(),
                    ROLE_SELLER,
                    seller.get().getEmail()
            ));
        }

        return Optional.empty();
    }

    public String resolveLoginId(Long memberId, String memberRole) {
        if (memberId == null || !StringUtils.hasText(memberRole)) {
            throw new CustomNotFoundException(ErrorCode.login_userNotFound);
        }

        if (ROLE_BUYER.equals(memberRole)) {
            return buyerRepository.findById(memberId)
                    .map(Buyer::getEmail)
                    .orElseThrow(() -> new CustomNotFoundException(ErrorCode.login_userNotFound));
        }

        if (ROLE_SELLER.equals(memberRole)) {
            return sellerRepository.findById(memberId)
                    .map(Seller::getEmail)
                    .orElseThrow(() -> new CustomNotFoundException(ErrorCode.login_userNotFound));
        }

        throw new CustomNotFoundException(ErrorCode.login_userNotFound);
    }

    public record MemberIdentity(
            Long memberId,
            String role,
            String loginId
    ) {
    }
}
