package com.woobeee.mvc.ai.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code /internal/ai/**} 는 워커만 부른다. 헤더 {@code X-Ai-Worker-Secret} 이 서버 시크릿과 일치해야 열린다(AI-AC-13).
 * 서버 시크릿이 비어 있으면 <b>모두 거절한다</b> — 설정을 빠뜨린 배포가 빈 헤더로 열리면 안 된다.
 * 접근 로그 필터({@code HIGHEST_PRECEDENCE}) 바로 안쪽에 둬서 거절된 요청도 로그에 남는다.
 *
 * <p>{@code @Component} 가 아니라 {@link AiFilterConfig} 가 등록한다 — {@code @WebMvcTest} 슬라이스는 Filter 빈을 끌어오는데
 * 그 안에는 {@link AiProperties} 가 없어서, 컴포넌트로 두면 무관한 컨트롤러 테스트의 컨텍스트가 깨진다.
 */
@RequiredArgsConstructor
public class AiWorkerSecretFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Ai-Worker-Secret";
    private static final String PATH_PREFIX = "/internal/ai/";
    // ApiResponse 실패 봉투와 같은 모양. 필터는 MessageConverter 밖에서 응답하므로 직접 직렬화한다.
    private static final String UNAUTHORIZED_BODY =
            "{\"header\":{\"isSuccessful\":false,\"message\":\"ai_workerUnauthorized\",\"resultCode\":401}}";

    private final AiProperties properties;

    /**
     * 컨테이너와 Spring MVC 는 퍼센트 디코딩·{@code ;param} 제거·{@code //}·{@code /./} 정규화를 거친 경로로 매칭한다.
     * 날 {@code getRequestURI()} 로 판정하면 {@code /internal/%61i/...} 같은 요청은 이 필터가 "내 일이 아니다" 하고
     * 건너뛰는데 컨트롤러는 그대로 실행돼 시크릿 검사가 통째로 우회된다. 같은 방식으로 정규화한 경로로 본다.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = StringUtils.cleanPath(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
        return !path.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String secret = properties.workerSecret();
        String given = request.getHeader(HEADER);
        // 길이가 달라도 같은 시간이 걸리게 MessageDigest.isEqual 로 비교한다.
        boolean ok = !secret.isEmpty() && given != null
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        }
        chain.doFilter(request, response);
    }
}
