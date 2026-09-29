package com.woobeee.mvc._common.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestAccessLogFilterTest {

    /**
     * 로그 필터는 토큰 필터보다 <b>바깥</b>이라 래퍼의 헤더가 보이지 않는다.
     * 신원은 원본 요청에 붙은 attribute 로 온다.
     */
    @Test
    void aLoggedInRequestIsIdentifiedByTheAttribute() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AccessTokenLoginIdHeaderFilter.LOGIN_ID_ATTRIBUTE, "someone@example.com");

        assertThat(RequestAccessLogFilter.identity(request)).isEqualTo("someone@example.com");
    }

    /** 헤더는 보루다 — 순서가 바뀌어 이 필터가 안쪽으로 가더라도 신원을 잃지 않는다. */
    @Test
    void theInjectedHeaderStillWorksAsAFallback() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("loginId", "someone@example.com");

        assertThat(RequestAccessLogFilter.identity(request)).isEqualTo("someone@example.com");
    }

    /** 비회원 경로가 정상이므로 "모름" 이 아니라 비회원으로 남긴다. */
    @Test
    void aRequestWithoutALoginIdIsLoggedAsAGuest() {
        assertThat(RequestAccessLogFilter.identity(new MockHttpServletRequest())).isEqualTo("비회원");
    }

    /** 빈 헤더도 비회원이다 — 공백만 있는 값에 속지 않는다. */
    @Test
    void aBlankLoginIdIsStillAGuest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("loginId", "   ");

        assertThat(RequestAccessLogFilter.identity(request)).isEqualTo("비회원");
    }

    /** 직접 접속이면 remoteAddr 이 곧 클라이언트다. */
    @Test
    void aDirectRequestUsesTheRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.9");

        assertThat(RequestAccessLogFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    /**
     * 프록시·터널을 거치면 remoteAddr 은 프록시다. X-Forwarded-For 는
     * `클라이언트, 프록시1, 프록시2` 로 쌓이므로 <b>첫</b> 값이 실제 클라이언트다.
     */
    @Test
    void aProxiedRequestUsesTheFirstForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.9, 70.41.3.18, 10.0.0.1");

        assertThat(RequestAccessLogFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    /** X-Real-IP 만 오는 프록시도 있다. */
    @Test
    void xRealIpIsUsedWhenThereIsNoForwardedForHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.9");

        assertThat(RequestAccessLogFilter.clientIp(request)).isEqualTo("203.0.113.9");
    }

    /** 헤더가 비어 있으면 없는 것으로 보고 remoteAddr 로 떨어진다. */
    @Test
    void anEmptyForwardedHeaderFallsBackToTheRemoteAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "  ");

        assertThat(RequestAccessLogFilter.clientIp(request)).isEqualTo("10.0.0.1");
    }
}
