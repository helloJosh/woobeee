package com.woobeee.mvc.ai.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** AI-AC-13 — 내부 API 는 워커 시크릿이 맞을 때만 열린다. */
class AiWorkerSecretFilterTest {

    private static AiWorkerSecretFilter filter(String secret) {
        return new AiWorkerSecretFilter(new AiProperties(null, null, 0, 0, 0, 0L, secret, null));
    }

    private static MockHttpServletRequest request(String uri, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        if (header != null) {
            request.addHeader("X-Ai-Worker-Secret", header);
        }
        return request;
    }

    @Test
    void AI_AC_13_correctSecretPasses() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("s3cret").doFilter(request("/internal/ai/requests/1/claim", "s3cret"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void AI_AC_13_wrongOrMissingSecretIs401WithTheCodeKey() throws Exception {
        for (String header : new String[]{"wrong", null, ""}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("s3cret").doFilter(request("/internal/ai/requests/1/claim", header), response, chain);

            assertThat(response.getStatus()).as("header=" + header).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("ai_workerUnauthorized");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void AI_AC_13_anUnsetServerSecretNeverOpensTheDoor() throws Exception {
        for (String serverSecret : new String[]{"", null}) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter(serverSecret).doFilter(request("/internal/ai/requests/1/claim", ""), response, chain);

            assertThat(response.getStatus()).as("serverSecret=" + serverSecret).isEqualTo(401);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void AI_AC_13_anEncodedOrParameterisedPathCannotSlipPastTheCheck() throws Exception {
        // 컨테이너와 Spring 은 디코딩·정규화한 경로로 매칭한다 — 날 문자열로 판정하면 이런 요청이 시크릿 검사를 건너뛴다
        for (String uri : new String[]{
                "/internal/%61i/requests/1/claim",
                "/internal/./ai/requests/1/claim",
                "/internal/x/../ai/requests/1/claim",
                "/internal;x=1/ai/requests/1/claim",
                "/internal/ai;jsessionid=1/requests/1/claim",
                "/internal//ai/requests/1/claim",
                "/%69nternal/ai/requests/1/claim",
        }) {
            FilterChain chain = mock(FilterChain.class);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter("s3cret").doFilter(request(uri, null), response, chain);

            assertThat(response.getStatus()).as(uri).isEqualTo(401);
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void AI_AC_13_otherPathsAreLeftAlone() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter("s3cret").doFilter(request("/api/back/ai/requests", null), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
