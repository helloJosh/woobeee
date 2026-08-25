package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import java.net.URI;
import java.net.MalformedURLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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

    /** 서명된 URL 한 개를 돌려주도록 presigner 를 스텁한다. 반환값은 그대로 본문에 박혀야 한다. */
    private static final String SIGNED =
            "https://image.woobeee.com/woobeee/13/a.png?X-Amz-Signature=deadbeef&X-Amz-Expires=3600";

    private ArgumentCaptor<GetObjectPresignRequest> stubPresigner(String signedUrl)
            throws MalformedURLException {
        PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
        when(presigned.url()).thenReturn(URI.create(signedUrl).toURL());
        ArgumentCaptor<GetObjectPresignRequest> captor =
                ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        when(s3Presigner.presignGetObject(captor.capture())).thenReturn(presigned);
        return captor;
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
     * BLOG-AC-13 — 본문 이미지 URL 은 <b>presigned URL</b> 이고, host 는 공개 endpoint 에서 온다.
     *
     * <p>이 자리는 두 번 갈아탔다. 처음엔 {@code https://woobeee.com} 이 코드에 박혀 있었고(그
     * apex 는 오리진이 없어 522), 다음엔 앱 스트리밍 상대경로였다. 지금은 브라우저가
     * {@code image.woobeee.com} 으로 MinIO 에 직접 붙는다.
     *
     * <p>여기서 고정하는 것은 <b>presigner 가 준 URL 을 손대지 않고 그대로 박는다</b>는 것이다.
     * 서명은 host 와 키를 포함하므로 문자열을 건드리면 서명이 깨진다.
     */
    @Test
    void postImageUrlsAreThePresignedUrlVerbatim() throws Exception {
        stubPresigner(SIGNED);

        GetPostResponse response = getPostWithOneImage("a.png");

        assertThat(response.content()).contains("](" + SIGNED + ")");
        assertThat(response.content()).doesNotContain("${");
    }

    /** BLOG-AC-13 — 서명 대상은 {@code {postId}/{basename}} 키다. */
    @Test
    void thePresignedRequestCarriesTheBucketAndKey() throws Exception {
        ArgumentCaptor<GetObjectPresignRequest> captor = stubPresigner(SIGNED);
        when(storageProperties.getBucket()).thenReturn("woobeee");
        when(storageProperties.getPresignedUrlExpirationSeconds()).thenReturn(3600L);

        getPostWithOneImage("a.png");

        GetObjectPresignRequest request = captor.getValue();
        assertThat(request.getObjectRequest().bucket()).isEqualTo("woobeee");
        assertThat(request.getObjectRequest().key()).isEqualTo("13/a.png");
        assertThat(request.signatureDuration()).isEqualTo(java.time.Duration.ofSeconds(3600));
    }

    /**
     * BLOG-AC-13 — 경로 성분이 섞인 플레이스홀더는 basename 만 서명한다.
     *
     * <p>{@code ${../profiles/1/x.png}} 를 그대로 서명하면 같은 버킷의 비공개 오브젝트를 여는
     * 유효한 URL 이 만들어진다. 서명이 접근을 허가하므로 버킷이 비공개여도 막지 못한다.
     */
    @Test
    void pathComponentsInAPlaceholderAreStrippedBeforeSigning() throws Exception {
        ArgumentCaptor<GetObjectPresignRequest> captor = stubPresigner(SIGNED);

        getPostWithOneImage("../profiles/1/secret.png");

        assertThat(captor.getValue().getObjectRequest().key()).isEqualTo("13/secret.png");
    }

    /**
     * BLOG-AC-13 — 파일명을 직접 퍼센트 인코딩하지 않는다. SDK 가 서명할 때 인코딩하므로,
     * 미리 인코딩해 넘기면 이중 인코딩이 되어 서명이 실제 키와 어긋난다.
     */
    @Test
    void nonAsciiFileNamesAreSignedRawNotPreEncoded() throws Exception {
        ArgumentCaptor<GetObjectPresignRequest> captor = stubPresigner(SIGNED);

        getPostWithOneImage("한글 그림.png");

        assertThat(captor.getValue().getObjectRequest().key()).isEqualTo("13/한글 그림.png");
    }


    /**
     * BLOG-AC-14 — 치환은 조회 시점에만 일어나고 저장 원문은 {@code ${파일명}} 을 유지한다.
     * 원문에 URL 을 구워 버리면 서빙 방식이 바뀔 때 기존 글이 전부 깨진다 — 이 자리가 두 번
     * 바뀌었는데도 기존 글이 멀쩡했던 이유가 이것이다. presigned URL 은 만료까지 있으므로
     * 원문에 굽는 것은 특히 안 된다.
     */
    @Test
    void theStoredMarkdownKeepsItsPlaceholders() throws Exception {
        stubPresigner(SIGNED);
        Posts post = postWithOneImage("a.png");

        when(postRepository.findById(13L)).thenReturn(Optional.of(post));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        GetPostResponse response = postService.getPost(13L, "ko", null, mock(HttpServletRequest.class));

        assertThat(response.content()).contains("](" + SIGNED + ")");
        assertThat(post.getTextKo()).isEqualTo("본문 ![a](${a.png}) 끝");
    }

    /**
     * BLOG-AC-15 — 목록 응답도 치환한다.
     *
     * <p>치환이 {@code getPost} 에만 걸려 있어서 목록에는 {@code ${파일명}} 원문이 그대로
     * 내려갔다. 목록 카드가 본문을 미리보기로 그리면 그 자리에 깨진 이미지가 뜬다.
     */
    @Test
    void theListResponseResolvesImagePlaceholdersToo() throws Exception {
        stubPresigner(SIGNED);
        Posts post = postWithOneImage("a.png");

        when(postRepository.searchPosts(null, null, "ko", Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(post)));
        when(categoryRepository.findById(1L)).thenReturn(Optional.empty());

        GetPostsResponse response = postService.getAllPost(null, "ko", null, Pageable.unpaged());

        assertThat(response.contents()).hasSize(1);
        assertThat(response.contents().getFirst().content())
                .contains("](" + SIGNED + ")")
                .doesNotContain("${");
    }



}
