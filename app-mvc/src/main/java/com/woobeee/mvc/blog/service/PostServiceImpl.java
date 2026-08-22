package com.woobeee.mvc.blog.service;


import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import com.woobeee.mvc.blog.api.response.GetPostsResponse;
import com.woobeee.mvc.blog.entity.Categories;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.exception.CustomInternalServerException;
import com.woobeee.mvc.blog.exception.CustomNotFoundException;
import com.woobeee.mvc.blog.exception.ErrorCode;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.blog.support.ProgressInputStream;
import com.woobeee.mvc.blog.support.RedisSupport;
import com.woobeee.mvc._common.storage.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class PostServiceImpl implements PostService {
    private final PostRepository postRepository;
    private final CategoryRepository categoryRepository;
    private final LikeRepository likeRepository;
    private final AuthMemberResolver authMemberResolver;

    private final RedisSupport redisSupport;
    private final S3Client s3Client;
    private final StorageProperties storageProperties;
    private final S3Presigner s3Presigner;

    /**
     * 이미지 삽입 글작성시: 마크다운에는 ![설명](${파일명}) 형태로 넣어두세요.
     */
    @SneakyThrows
    @Override
    public void savePost (
            PostPostRequest request,
            String loginId,
            MultipartFile markdownEn,
            MultipartFile markdownKr,
            List<MultipartFile> files
    ) {
        AuthMemberResolver.MemberIdentity memberIdentity = authMemberResolver.requireByLoginId(loginId);

        String markdownEnString = readMarkdown(markdownEn);
        String markdownKrString = readMarkdown(markdownKr);

        Posts post = new Posts(
                request.titleKo(),
                request.titleEn(),
                markdownKrString == null ? "" : markdownKrString,
                markdownEnString == null ? "" : markdownEnString,
                request.categoryId(),
                memberIdentity.memberId()
        );

        post = postRepository.save(post);

        uploadAttachments(post.getId(), files);
        postRepository.save(post);
    }

    @SneakyThrows
    @Override
    public void updatePost(
            Long postId,
            PostPostRequest request,
            String loginId,
            MultipartFile markdownEn,
            MultipartFile markdownKr,
            List<MultipartFile> files
    ) {
        AuthMemberResolver.MemberIdentity memberIdentity = authMemberResolver.requireByLoginId(loginId);

        Posts post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomNotFoundException(ErrorCode.post_notFound));

        if (!post.getMemberId().equals(memberIdentity.memberId())) {
            throw new CustomAuthenticationException(ErrorCode.comment_needAuthentication);
        }

        post.updateContent(
                request.titleKo(),
                request.titleEn(),
                readMarkdown(markdownKr),
                readMarkdown(markdownEn),
                request.categoryId()
        );

        uploadAttachments(post.getId(), files);
        postRepository.save(post);
    }

    @SneakyThrows
    private String readMarkdown(MultipartFile markdown) {
        return (markdown != null && !markdown.isEmpty())
                ? new String(markdown.getBytes(), StandardCharsets.UTF_8)
                : null;
    }

    /**
     * 마크다운의 ${파일명} 플레이스홀더는 조회 시 {postId}/{파일명} 공개 URL 로 치환된다.
     */
    private void uploadAttachments(Long postId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }

        AtomicInteger lastPrintedPercent = new AtomicInteger(-1);

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) continue;

            String original = file.getOriginalFilename();
            if (original == null) continue;
            String fileName = Paths.get(original).getFileName().toString().trim(); // 경로 제거

            String key = postId + "/" + fileName;

            try (
                    InputStream is = file.getInputStream();
                    ProgressInputStream pis = new ProgressInputStream(
                            is,
                            file.getSize(),
                            percent -> {
                                int p = (int) percent.doubleValue();
                                if (p != lastPrintedPercent.getAndSet(p)) {
                                    log.info("Upload progress: {}%", p);
                                }
                            }
                    )
            ) {
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(storageProperties.getBucket())
                                .key(key)
                                .contentType(file.getContentType())
                                .build(),
                        RequestBody.fromInputStream(pis, file.getSize())
                );
            } catch (IOException e) {
                throw new CustomInternalServerException(ErrorCode.post_imageUploadError);
            }
        }
    }

    @Override
    public void deletePost(Long postId, String loginId) {
        AuthMemberResolver.MemberIdentity memberIdentity = authMemberResolver.requireByLoginId(loginId);

        Posts post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomNotFoundException(ErrorCode.post_notFound));

        if (!post.getMemberId().equals(memberIdentity.memberId())) {
            throw new CustomAuthenticationException(ErrorCode.comment_needAuthentication);
        }

        postRepository.delete(post);
    }

    @Override
    @Transactional(readOnly = true)
    public GetPostResponse getPost(Long postId, String locale, String loginId, HttpServletRequest request) {
        Posts post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomNotFoundException(ErrorCode.post_notFound));

        long redisAfter = redisSupport.incrementPostViewAndRanking(postId, request);

        String title = locale.equalsIgnoreCase("en") ? post.getTitleEn() : post.getTitleKo();
        String content = locale.equalsIgnoreCase("en") ? post.getTextEn() : post.getTextKo();

        content = replaceImagePlaceholdersWithPresignedUrls(content, postId);

        String categoryName = categoryRepository.findById(post.getCategoryId())
                .map(cat -> locale.equalsIgnoreCase("en") ? cat.getNameEn() : cat.getNameKo())
                .orElse("Unknown");

        Long likeCount = likeRepository.countByPostId(post.getId());

        Boolean isLiked = false;
        if (loginId != null) {
            isLiked = authMemberResolver.findByLoginId(loginId)
                    .map(memberIdentity -> likeRepository.existsByMemberIdAndPostId(
                            memberIdentity.memberId(),
                            post.getId()
                    ))
                    .orElse(false);
        }

        //TODO: view batch로 redis에서 가져와서 업데이트
        return new GetPostResponse(
                post.getId(),
                title,
                content,
                categoryName,
                post.getCategoryId(),
                redisAfter,
                likeCount,
                isLiked,
                post.getCreatedAt()
        );
    }
//
//    private String replaceLocalhostToDev(String markdown) {
//        return markdown.replace("http://localhost:9000", "https://woobeee.com");
//    }

    private String replaceImagePlaceholdersWithPresignedUrls(String markdown, Long postId) {
        if (markdown == null || markdown.isBlank()) return markdown;

        Pattern pattern = Pattern.compile("\\$\\{(.+?)\\}");
        Matcher matcher = pattern.matcher(markdown);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String fileName = matcher.group(1); // ${fileName} 에서 fileName 추출

            // Presigned URL 생성
            //String presignedUrl = generatePresignedUrl(postId, fileName);

            // public URL
            String publicUrl = publicUrl(postId, fileName);

            matcher.appendReplacement(result, Matcher.quoteReplacement(publicUrl));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 본문에 박히는 이미지 주소. 이 앱의 스트리밍 엔드포인트를 가리키는 <b>상대 경로</b>다.
     *
     * <p>절대 URL 을 쓰지 않는 것이 요점이다. 전에는 {@code https://woobeee.com} 이 박혀
     * 있었고(그 apex 는 오리진이 없어 루트까지 522 다), 그 다음에는 도메인을 설정으로 뺐지만
     * 그 방식은 버킷 익명 읽기까지 열어야 동작했다. 상대 경로는 프론트가 이미 프록시하는
     * {@code /api/back/*} 를 타므로 도메인 설정도, 버킷 공개도, 프록시 규칙 추가도 필요 없고
     * 로컬과 프로덕션이 같은 값으로 동작한다.
     *
     * <p>파일명은 퍼센트 인코딩한다 -- 업로드가 원본 basename 을 키로 쓰므로 한글이나 공백이
     * 들어올 수 있다. {@link URLEncoder} 는 공백을 {@code +} 로 만드는데 경로에서는 리터럴
     * {@code +} 로 읽히므로 {@code %20} 으로 바꾼다.
     */
    private String publicUrl(Long postId, String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return String.format("/api/back/posts/%d/images/%s", postId, encoded);
    }

    /**
     * 버킷은 비공개로 두고 앱이 자격증명으로 오브젝트를 대신 읽는다.
     *
     * <p>파일명은 basename 만 남긴다. 그대로 이어 붙이면 {@code 13/../profiles/1/x.png} 로
     * 같은 버킷의 비공개 오브젝트를 인증 없이 읽을 수 있다.
     */
    @Override
    @Transactional(readOnly = true)
    public PostImage loadPostImage(Long postId, String fileName) {
        String safeName = Paths.get(fileName).getFileName().toString().trim();
        String key = postId + "/" + safeName;

        try {
            ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder()
                            .bucket(storageProperties.getBucket())
                            .key(key)
                            .build()
            );

            return new PostImage(object.asByteArray(), object.response().contentType());
        } catch (NoSuchKeyException exception) {
            throw new CustomNotFoundException(ErrorCode.post_imageNotFound);
        }
    }

    private String generatePresignedUrl(Long postId, String fileName) {
        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(postId + "/" + fileName)
                .build();

        GetObjectPresignRequest preReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofDays(7))
                .getObjectRequest(getReq)
                .build();

        return s3Presigner.presignGetObject(preReq).url().toString();
    }

    @Override
    @Transactional(readOnly = true)
    public GetPostsResponse getAllPost(String q, String locale, Long categoryId, Pageable pageable) {
        Page<Posts> posts;
        List<Long> categories = categoryId == null ? null : findAllChildIdsIncludingSelf(categoryId);
        posts = postRepository.searchPosts(categories, q, locale, pageable);

        List<GetPostsResponse.PostContent> contents = posts.getContent().stream().map(post -> {
            String title = locale.equalsIgnoreCase("en") ? post.getTitleEn() : post.getTitleKo();
            // 목록도 치환한다. 안 하면 미리보기에 ${파일명} 원문이 그대로 나간다.
            String content = replaceImagePlaceholdersWithPresignedUrls(
                    locale.equalsIgnoreCase("en") ? post.getTextEn() : post.getTextKo(),
                    post.getId()
            );
            String categoryName = categoryRepository.findById(post.getCategoryId())
                    .map(cat -> locale.equalsIgnoreCase("en") ? cat.getNameEn() : cat.getNameKo())
                    .orElse("Unknown");

            long redisAfter = redisSupport.getCurrentPostView(post.getId());
            Long likeCount = likeRepository.countByPostId(post.getId());

            return new GetPostsResponse.PostContent(
                    post.getId(),
                    title,
                    content,
                    categoryName,
                    post.getCategoryId(),
                    redisAfter,
                    likeCount,
                    post.getCreatedAt()
            );
        }).toList();

        return new GetPostsResponse(posts.hasNext(), contents);
    }

    public List<Long> findAllChildIdsIncludingSelf(Long parentId) {
        List<Long> ids = new ArrayList<>();
        ids.add(parentId);
        List<Categories> children = categoryRepository.findAllByParentId(parentId);
        for (Categories child : children) {
            ids.addAll(findAllChildIdsIncludingSelf(child.getId()));
        }
        return ids;
    }
}
