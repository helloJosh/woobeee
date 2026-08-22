package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.blog.support.RedisSupport;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.woobeee.mvc._common.storage.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {
    @Mock
    private PostRepository postRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private LikeRepository likeRepository;

    @Mock
    private AuthMemberResolver authMemberResolver;

    @Mock
    private RedisSupport redisSupport;

    @Mock
    private S3Client s3Client;

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private S3Presigner s3Presigner;

    @InjectMocks
    private PostServiceImpl postService;

    private Posts existingPost(long postId, long authorId) {
        Posts post = new Posts("old-ko", "old-en", "old text ko", "old text en", 1L, authorId);
        ReflectionTestUtils.setField(post, "id", postId);
        return post;
    }

    /** BLOG-AC-11 */
    @Test
    void updatePostUpdatesTitlesContentAndCategory() {
        Posts post = existingPost(42L, 3L);
        when(authMemberResolver.requireByLoginId("admin@example.com"))
                .thenReturn(new AuthMemberResolver.MemberIdentity(3L, "admin@example.com"));
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));

        postService.updatePost(
                42L,
                new PostPostRequest("new-ko", "new-en", 5L),
                "admin@example.com",
                new MockMultipartFile("markdownEn", "en.md", "text/markdown", "# new en".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("markdownKr", "kr.md", "text/markdown", "# new kr".getBytes(StandardCharsets.UTF_8)),
                null
        );

        assertThat(post.getTitleKo()).isEqualTo("new-ko");
        assertThat(post.getTitleEn()).isEqualTo("new-en");
        assertThat(post.getTextKo()).isEqualTo("# new kr");
        assertThat(post.getTextEn()).isEqualTo("# new en");
        assertThat(post.getCategoryId()).isEqualTo(5L);
    }

    /** BLOG-AC-11 */
    @Test
    void updatePostByNonAuthorIsRejected() {
        Posts post = existingPost(42L, 3L);
        when(authMemberResolver.requireByLoginId("other@example.com"))
                .thenReturn(new AuthMemberResolver.MemberIdentity(9L, "other@example.com"));
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));

        assertThatThrownBy(() -> postService.updatePost(
                42L,
                new PostPostRequest("new-ko", "new-en", 5L),
                "other@example.com",
                null,
                null,
                null
        )).isInstanceOf(CustomAuthenticationException.class);

        assertThat(post.getTitleKo()).isEqualTo("old-ko");
    }

    /** BLOG-AC-11 — 마크다운 파트가 없으면 본문은 보존한다 */
    @Test
    void updatePostWithoutMarkdownPartsKeepsExistingContent() {
        Posts post = existingPost(42L, 3L);
        when(authMemberResolver.requireByLoginId("admin@example.com"))
                .thenReturn(new AuthMemberResolver.MemberIdentity(3L, "admin@example.com"));
        when(postRepository.findById(42L)).thenReturn(Optional.of(post));

        postService.updatePost(
                42L,
                new PostPostRequest("new-ko", "new-en", 5L),
                "admin@example.com",
                null,
                null,
                null
        );

        assertThat(post.getTextKo()).isEqualTo("old text ko");
        assertThat(post.getTextEn()).isEqualTo("old text en");
        assertThat(post.getTitleKo()).isEqualTo("new-ko");
    }

    /** 이미지 URL 을 조립하는 데 필요한 최소 모킹. 본문에 {@code ${a.png}} 하나가 들어 있다. */
    private GetPostResponse getPostWithOneImage(String publicBaseUrl) {
        Posts post = new Posts("ko", "en", "본문 ![a](${a.png}) 끝", "body", 1L, 3L);
        ReflectionTestUtils.setField(post, "id", 13L);

        when(postRepository.findById(13L)).thenReturn(Optional.of(post));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());
        when(storageProperties.getPublicBaseUrl()).thenReturn(publicBaseUrl);
        when(storageProperties.getBucket()).thenReturn("woobeee");

        return postService.getPost(13L, "ko", null, mock(HttpServletRequest.class));
    }

    /**
     * BLOG-AC-13 — 이미지 URL 의 base 는 설정값에서 온다.
     *
     * <p>전에는 {@code https://woobeee.com} 이 코드에 박혀 있었고, 그 apex 는 오리진이 없어
     * 루트까지 522 를 낸다. 하드코딩이 남아 있으면 이 테스트가 그것을 잡는다.
     */
    @Test
    void postImageUrlsUseTheConfiguredPublicBase() {
        GetPostResponse response = getPostWithOneImage("https://www.woobeee.com");

        assertThat(response.content()).contains("https://www.woobeee.com/woobeee/13/a.png");
        assertThat(response.content()).doesNotContain("https://woobeee.com/woobeee");
    }

    /** BLOG-AC-13 — base 를 슬래시로 끝내도 {@code //} 가 겹치지 않는다. */
    @Test
    void aTrailingSlashOnThePublicBaseDoesNotDoubleUp() {
        GetPostResponse response = getPostWithOneImage("http://localhost:9000/");

        assertThat(response.content()).contains("http://localhost:9000/woobeee/13/a.png");
        assertThat(response.content()).doesNotContain("9000//woobeee");
    }

    /**
     * BLOG-AC-14 — 치환은 조회 시점에만 일어나고 저장 원문은 {@code ${파일명}} 을 유지한다.
     * 원문에 URL 을 구워 버리면 도메인이 바뀔 때 기존 글이 전부 깨진다.
     */
    @Test
    void theStoredMarkdownKeepsItsPlaceholders() {
        Posts post = new Posts("ko", "en", "본문 ![a](${a.png}) 끝", "body", 1L, 3L);
        ReflectionTestUtils.setField(post, "id", 13L);

        when(postRepository.findById(13L)).thenReturn(Optional.of(post));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());
        when(storageProperties.getPublicBaseUrl()).thenReturn("https://www.woobeee.com");
        when(storageProperties.getBucket()).thenReturn("woobeee");

        GetPostResponse response = postService.getPost(13L, "ko", null, mock(HttpServletRequest.class));

        assertThat(response.content()).contains("https://www.woobeee.com/woobeee/13/a.png");
        assertThat(post.getTextKo()).isEqualTo("본문 ![a](${a.png}) 끝");
    }
}
