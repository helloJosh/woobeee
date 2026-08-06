package com.woobeee.mvc._common.filter;

import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessTokenLoginIdHeaderFilterTest {
    @Mock
    private TokenStore tokenStore;

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private AccessTokenLoginIdHeaderFilter filter;

    private AccessTokenLoginIdHeaderFilter newFilter() {
        return new AccessTokenLoginIdHeaderFilter(tokenStore, memberRepository);
    }

    private void stubToken(String token, long memberId, String role) {
        when(tokenStore.find(token, AuthTokenType.ACCESS))
                .thenReturn(Optional.of(new TokenSnapshot(
                        new TokenMetadata(memberId, role, "web", "127.0.0.1"), 900L)));
    }

    private void stubToken(String token, long memberId, String role, String email) {
        stubToken(token, memberId, role);
        Member member = Member.create("sub-" + memberId, email, "nick", true, true);
        ReflectionTestUtils.setField(member, "id", memberId);
        when(memberRepository.findById(memberId)).thenReturn(Optional.of(member));
    }

    /** BLOG-AC-07 */
    @Test
    void clientSentLoginIdHeaderIsStrippedWhenThereIsNoToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/back/comments");
        request.addHeader("loginId", "forged@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getHeader("loginId")).isNull();
    }

    /** BLOG-AC-07 */
    @Test
    void clientSentLoginIdHeaderIsReplacedByTokenDerivedIdentity() throws Exception {
        stubToken("valid-token", 7L, "ROLE_MEMBER", "real@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/back/comments");
        request.addHeader("loginId", "forged@example.com");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getHeader("loginId"))
                .isEqualTo("real@example.com");
    }

    /** BLOG-AC-08 */
    @Test
    void memberRoleTokenIsForbiddenFromPostWrites() throws Exception {
        stubToken("member-token", 7L, "ROLE_MEMBER");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/back/posts");
        request.addHeader("Authorization", "Bearer member-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("\"isSuccessful\":false");
    }

    /** BLOG-AC-09 */
    @Test
    void adminRoleTokenPassesPostWrites() throws Exception {
        stubToken("admin-token", 3L, "ROLE_ADMIN", "admin@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/back/posts/42");
        request.addHeader("Authorization", "Bearer admin-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(((jakarta.servlet.http.HttpServletRequest) chain.getRequest()).getHeader("loginId"))
                .isEqualTo("admin@example.com");
    }

    /** BLOG-AC-08 */
    @Test
    void anonymousRequestIsForbiddenFromPostWrites() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/back/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    /** BLOG-AC-10 */
    @Test
    void memberRoleTokenStillPassesCommentWrites() throws Exception {
        stubToken("member-token", 7L, "ROLE_MEMBER", "member@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/back/comments");
        request.addHeader("Authorization", "Bearer member-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    /** BLOG-AC-09 */
    @Test
    void anonymousReadsStayOpen() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/back/posts");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        newFilter().doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void adminOnlyWriteMatrixCoversPostsAndCategories() {
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("POST", "/api/back/posts")).isTrue();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("PUT", "/api/back/posts/42")).isTrue();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("DELETE", "/api/back/posts/42")).isTrue();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("POST", "/api/back/categories/1")).isTrue();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("DELETE", "/api/back/categories/1")).isTrue();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("GET", "/api/back/posts")).isFalse();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("GET", "/api/back/categories")).isFalse();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("POST", "/api/back/comments")).isFalse();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("POST", "/api/back/likes/3")).isFalse();
        assertThat(AccessTokenLoginIdHeaderFilter.requiresAdmin("POST", "/api/auth/login")).isFalse();
    }
}
