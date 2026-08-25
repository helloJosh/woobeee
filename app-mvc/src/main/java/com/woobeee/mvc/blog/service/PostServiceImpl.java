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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.io.InputStream;
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
     * 본문에 박히는 이미지 주소. <b>presigned URL</b> 이다 -- 브라우저가 MinIO 에 직접 붙는다.
     *
     * <p>host 는 서버용 {@code endpoint} 가 아니라 {@code public-endpoint}(프로덕션:
     * {@code https://image.woobeee.com}) 에서 나온다. 서명이 host 를 포함하므로 만든 뒤에
     * 문자열로 갈아끼울 수 없고, presigner 를 만들 때 정해진다({@code StorageConfig}).
     *
     * <p>서명이 접근을 허가하므로 <b>버킷은 비공개로 남는다</b>. 같은 버킷의 {@code profiles/}
     * 도 공개되지 않는다.
     *
     * <p>파일명 인코딩은 SDK 가 키를 서명할 때 처리하므로 여기서 손대지 않는다 -- 직접 인코딩해
     * 넘기면 이중 인코딩이 되어 서명이 실제 키와 어긋난다.
     */
    private String publicUrl(Long postId, String fileName) {
        return generatePresignedUrl(postId, fileName);
    }


    private String generatePresignedUrl(Long postId, String fileName) {
        // basename 만 남긴다. 본문의 ${..} 가 경로 성분을 담고 있으면 같은 버킷의 다른
        // prefix(profiles/ 등)를 여는 유효한 서명이 만들어진다 -- 버킷이 비공개여도 서명이
        // 접근을 허가하므로 막히지 않는다.
        String safeName = Paths.get(fileName).getFileName().toString().trim();

        GetObjectRequest getReq = GetObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(postId + "/" + safeName)
                .build();

        GetObjectPresignRequest preReq = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(storageProperties.getPresignedUrlExpirationSeconds()))
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
