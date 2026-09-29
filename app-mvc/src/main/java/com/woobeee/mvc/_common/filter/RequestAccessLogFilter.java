package com.woobeee.mvc._common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 모든 요청을 한 줄로 남긴다: 메서드, 경로, 상태, 소요 시간, 클라이언트 IP, 신원.
 *
 * <p>신원은 {@link AccessTokenLoginIdHeaderFilter} 가 유효한 access token 에서 파생해 넣어 준
 * loginId 다. 없으면 {@code 비회원} 이다 — pokemon 처럼 로그인 없이 쓰는 경로가 있으므로
 * "누가 했는지 모름" 이 정상적인 값이고, 그때는 IP 만이 단서다.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE} 로 가장 <b>바깥</b>에 둔다. 안쪽에 두면 토큰 필터가
 * 401/403 으로 잘라낸 요청이 아예 로그에 남지 않는데, 거절된 요청이야말로 남아야 한다.
 *
 * <p>바깥에 있으면 토큰 필터가 <b>아래로</b> 넘기는 래퍼의 loginId 헤더는 보이지 않는다.
 * 그래서 신원은 {@link AccessTokenLoginIdHeaderFilter#LOGIN_ID_ATTRIBUTE} 로 읽는다 —
 * attribute 는 원본 요청에 붙으므로 체인이 끝난 뒤에도 읽힌다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestAccessLogFilter extends OncePerRequestFilter {
    private static final String LOGIN_ID_HEADER = "loginId";
    private static final String GUEST = "비회원";
    /** 프록시·터널을 거쳐 오면 remoteAddr 은 프록시 주소다. 실제 클라이언트는 이 헤더에 있다. */
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String REAL_IP = "X-Real-IP";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            log.info("{} {}{} -> {} ({}ms) ip={} id={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    queryPart(request),
                    response.getStatus(),
                    System.currentTimeMillis() - startedAt,
                    clientIp(request),
                    identity(request));
        }
    }

    private static String queryPart(HttpServletRequest request) {
        String query = request.getQueryString();
        return StringUtils.hasText(query) ? "?" + query : "";
    }

    /**
     * 로그인이면 loginId, 아니면 비회원.
     *
     * <p>attribute 를 먼저 본다 — 이 필터는 토큰 필터보다 바깥이라 래퍼에 붙은 헤더가
     * 보이지 않는다. 헤더는 순서가 바뀌어 이 필터가 안쪽으로 가는 경우의 보루다.
     */
    static String identity(HttpServletRequest request) {
        Object fromAttribute = request.getAttribute(AccessTokenLoginIdHeaderFilter.LOGIN_ID_ATTRIBUTE);
        if (fromAttribute instanceof String loginId && StringUtils.hasText(loginId)) {
            return loginId;
        }
        String fromHeader = request.getHeader(LOGIN_ID_HEADER);
        return StringUtils.hasText(fromHeader) ? fromHeader : GUEST;
    }

    /**
     * X-Forwarded-For 는 {@code 클라이언트, 프록시1, 프록시2} 로 쌓이므로 <b>첫</b> 값이
     * 실제 클라이언트다. 헤더가 없으면 직접 접속이므로 remoteAddr 을 쓴다.
     */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR);
        if (StringUtils.hasText(forwarded)) {
            String first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader(REAL_IP);
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
