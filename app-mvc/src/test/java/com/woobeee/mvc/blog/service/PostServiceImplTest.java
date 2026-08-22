package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.exception.CustomNotFoundException;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.blog.support.RedisSupport;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import com.woobeee.mvc.blog.api.response.GetPostsResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.woobeee.mvc._common.storage.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private Posts postWithOneImage(String placeholderFileName) {
        Posts post = new Posts("ko", "en", "본문 ![a](${" + placeholderFileName + "}) 끝", "body", 1L, 3L);
        ReflectionTestUtils.setField(post, "id", 13L);
        return post;
    }

    private GetPostResponse getPostWithOneImage(String placeholderFileName) {
        Posts post = postWithOneImage(placeholderFileName);

        when(postRepository.findById(13L)).thenReturn(Optional.of(post));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        return postService.getPost(13L, "ko", null, mock(HttpServletRequest.class));
    }

    /**
     * BLOG-AC-13 — 본문 이미지 URL 은 app-mvc 의 스트리밍 엔드포인트를 가리키는 <b>상대 경로</b>다.
     *
     * <p>전에는 {@code https://woobeee.com} 이 코드에 박혀 있었다. 그 apex 는 오리진이 없어
     * 루트까지 522 를 내므로 이미지가 전부 깨졌다. 도메인을 설정으로 빼는 것으로도 부족했는데,
     * 그 방식은 버킷 익명 읽기(인프라 작업)까지 있어야 열렸기 때문이다. 상대 경로는 프론트가
     * 이미 프록시하는 {@code /api/back/*} 를 타므로 도메인도, 버킷 공개도 필요하지 않다.
     *
     * <p>절대 URL 이 돌아오면 이 테스트가 잡는다 — 호스트가 박히는 순간 로컬/프로덕션 중
     * 한쪽이 깨진다.
     */
    @Test
    void postImageUrlsPointAtTheAppStreamingEndpoint() {
        GetPostResponse response = getPostWithOneImage("a.png");

        assertThat(response.content()).contains("](/api/back/posts/13/images/a.png)");
        assertThat(response.content()).doesNotContain("http://");
        assertThat(response.content()).doesNotContain("https://");
    }

    /**
     * BLOG-AC-13 — 한글·공백 파일명은 퍼센트 인코딩된다. 블로그 업로드는 프로필 이미지와 달리
     * 파일명을 ASCII 로 강제하지 않으므로(원본 basename 을 그대로 키로 쓴다) 인코딩이 필요하다.
     * 공백이 {@code +} 로 나가면 경로에서는 리터럴 {@code +} 로 읽혀 키가 어긋난다.
     */
    @Test
    void nonAsciiImageFileNamesArePercentEncoded() {
        GetPostResponse response = getPostWithOneImage("한글 그림.png");

        assertThat(response.content()).contains("](/api/back/posts/13/images/%ED%95%9C%EA%B8%80%20%EA%B7%B8%EB%A6%BC.png)");
        assertThat(response.content()).doesNotContain("+");
    }

    /**
     * BLOG-AC-14 — 치환은 조회 시점에만 일어나고 저장 원문은 {@code ${파일명}} 을 유지한다.
     * 원문에 URL 을 구워 버리면 서빙 방식이 바뀔 때 기존 글이 전부 깨진다.
     */
    @Test
    void theStoredMarkdownKeepsItsPlaceholders() {
        Posts post = postWithOneImage("a.png");

        when(postRepository.findById(13L)).thenReturn(Optional.of(post));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        GetPostResponse response = postService.getPost(13L, "ko", null, mock(HttpServletRequest.class));

        assertThat(response.content()).contains("](/api/back/posts/13/images/a.png)");
        assertThat(post.getTextKo()).isEqualTo("본문 ![a](${a.png}) 끝");
    }

    /**
     * BLOG-AC-15 — 목록 응답도 치환한다.
     *
     * <p>치환이 {@code getPost} 에만 걸려 있어서 목록에는 {@code ${파일명}} 원문이 그대로
     * 내려갔다. 목록 카드가 본문을 미리보기로 그리면 그 자리에 깨진 이미지가 뜬다.
     */
    @Test
    void theListResponseResolvesImagePlaceholdersToo() {
        Posts post = postWithOneImage("a.png");

        when(postRepository.searchPosts(null, null, "ko", Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(post)));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        GetPostsResponse response = postService.getAllPost(null, "ko", null, Pageable.unpaged());

        assertThat(response.contents()).hasSize(1);
        assertThat(response.contents().getFirst().content())
                .contains("](/api/back/posts/13/images/a.png)")
                .doesNotContain("${");
    }

    /** BLOG-AC-16 — 스트리밍 엔드포인트는 오브젝트 바이트와 저장된 contentType 을 함께 준다. */
    @Test
    void loadPostImageReturnsTheObjectBytesAndItsContentType() {
        when(storageProperties.getBucket()).thenReturn("woobeee");
        when(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("woobeee")
                .key("13/a.png")
                .build()))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType("image/png").build(),
                        new byte[]{1, 2, 3}
                ));

        PostService.PostImage image = postService.loadPostImage(13L, "a.png");

        assertThat(image.bytes()).containsExactly(1, 2, 3);
        assertThat(image.contentType()).isEqualTo("image/png");
    }

    /**
     * BLOG-AC-16 — 파일명에 경로가 섞여 오면 basename 만 남긴다. 그대로 키에 이어 붙이면
     * {@code 13/../profiles/1/x.png} 로 같은 버킷의 비공개 오브젝트를 인증 없이 읽을 수 있다.
     */
    @Test
    void loadPostImageStripsPathsFromTheFileName() {
        when(storageProperties.getBucket()).thenReturn("woobeee");
        when(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("woobeee")
                .key("13/x.png")
                .build()))
                .thenReturn(ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType("image/png").build(),
                        new byte[]{9}
                ));

        assertThat(postService.loadPostImage(13L, "../profiles/1/x.png").bytes()).containsExactly(9);
    }

    /** BLOG-AC-16 — 없는 오브젝트는 500 이 아니라 404 다. */
    @Test
    void aMissingImageObjectBecomesNotFound() {
        when(storageProperties.getBucket()).thenReturn("woobeee");
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("nope").build());

        assertThatThrownBy(() -> postService.loadPostImage(13L, "missing.png"))
                .isInstanceOf(CustomNotFoundException.class);
    }
}
