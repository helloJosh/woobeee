package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.blog.support.RedisSupport;
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
}
