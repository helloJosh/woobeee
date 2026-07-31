package com.woobeee.mvc._common.filter;

import com.woobeee.mvc.auth.entity.Buyer;
import com.woobeee.mvc.auth.entity.Seller;
import com.woobeee.mvc.auth.repository.BuyerRepository;
import com.woobeee.mvc.auth.repository.SellerRepository;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AccessTokenLoginIdHeaderFilter extends OncePerRequestFilter {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String LOGIN_ID_HEADER = "loginId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_BUYER = "ROLE_BUYER";
    private static final String ROLE_SELLER = "ROLE_SELLER";

    private final TokenStore tokenStore;
    private final BuyerRepository buyerRepository;
    private final SellerRepository sellerRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (StringUtils.hasText(request.getHeader(LOGIN_ID_HEADER))) {
            filterChain.doFilter(request, response);
            return;
        }

        String accessToken = resolveAccessToken(request);
        if (!StringUtils.hasText(accessToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<TokenMetadata> metadata = tokenStore.find(accessToken, AuthTokenType.ACCESS)
                .map(snapshot -> snapshot.metadata());
        if (metadata.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String loginId = resolveLoginId(metadata.get());
        if (!StringUtils.hasText(loginId)) {
            filterChain.doFilter(request, response);
            return;
        }

        MutableHttpServletRequest wrappedRequest = new MutableHttpServletRequest(request);
        wrappedRequest.putHeader(LOGIN_ID_HEADER, loginId);
        filterChain.doFilter(wrappedRequest, response);
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    private String resolveLoginId(TokenMetadata metadata) {
        if (ROLE_BUYER.equals(metadata.role())) {
            return buyerRepository.findById(metadata.memberId())
                    .map(Buyer::getEmail)
                    .orElse(null);
        }

        if (ROLE_SELLER.equals(metadata.role())) {
            return sellerRepository.findById(metadata.memberId())
                    .map(Seller::getEmail)
                    .orElse(null);
        }

        return null;
    }
}
