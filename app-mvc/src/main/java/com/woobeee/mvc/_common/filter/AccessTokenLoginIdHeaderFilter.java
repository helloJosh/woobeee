package com.woobeee.mvc._common.filter;

import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.entity.MemberRole;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * loginId 헤더는 이 필터가 유효한 access token 에서 파생해 주입하는 서버 내부 값이다.
 * 클라이언트가 보낸 loginId 헤더는 항상 제거한다 — 신뢰하면 타인 신원 위조가 된다.
 */
@Component
@RequiredArgsConstructor
public class AccessTokenLoginIdHeaderFilter extends OncePerRequestFilter {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String LOGIN_ID_HEADER = "loginId";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ADMIN_WRITE_PATH_PREFIX_POSTS = "/api/back/posts";
    private static final String ADMIN_WRITE_PATH_PREFIX_CATEGORIES = "/api/back/categories";

    // ApiResponse 실패 봉투와 같은 모양. 필터는 MessageConverter 밖에서 응답하므로 직접 직렬화한다.
    private static final String FORBIDDEN_BODY =
            "{\"header\":{\"isSuccessful\":false,\"message\":\"Admin role is required\",\"resultCode\":403}}";

    private final TokenStore tokenStore;
    private final MemberRepository memberRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        MutableHttpServletRequest wrappedRequest = new MutableHttpServletRequest(request);
        wrappedRequest.removeHeader(LOGIN_ID_HEADER);

        Optional<TokenMetadata> metadata = Optional.ofNullable(resolveAccessToken(request))
                .flatMap(token -> tokenStore.find(token, AuthTokenType.ACCESS))
                .map(snapshot -> snapshot.metadata());

        if (requiresAdmin(request.getMethod(), request.getRequestURI())
                && metadata.map(TokenMetadata::role)
                        .filter(MemberRole.ROLE_ADMIN.name()::equals)
                        .isEmpty()) {
            writeForbidden(response);
            return;
        }

        metadata.map(this::resolveLoginId)
                .filter(StringUtils::hasText)
                .ifPresent(loginId -> wrappedRequest.putHeader(LOGIN_ID_HEADER, loginId));

        filterChain.doFilter(wrappedRequest, response);
    }

    static boolean requiresAdmin(String method, String path) {
        boolean adminPath = path.startsWith(ADMIN_WRITE_PATH_PREFIX_POSTS)
                || path.startsWith(ADMIN_WRITE_PATH_PREFIX_CATEGORIES);
        boolean writeMethod = "POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method);
        return adminPath && writeMethod;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(FORBIDDEN_BODY);
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
        if (metadata.memberId() == null) {
            return null;
        }

        return memberRepository.findById(metadata.memberId())
                .map(Member::getEmail)
                .orElse(null);
    }
}
