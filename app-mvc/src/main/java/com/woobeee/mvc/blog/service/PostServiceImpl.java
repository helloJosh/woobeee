package com.woobeee.mvc.blog.service;


import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import com.woobeee.mvc.blog.api.response.GetPostsResponse;
import com.woobeee.mvc.blog.api.response.TagResponse;
import com.woobeee.mvc.blog.entity.Categories;
import com.woobeee.mvc.blog.entity.PostTags;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.entity.Tags;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.exception.CustomInternalServerException;
import com.woobeee.mvc.blog.exception.CustomNotFoundException;
import com.woobeee.mvc.blog.exception.ErrorCode;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import com.woobeee.mvc.blog.repository.LikeRepository;
import com.woobeee.mvc.blog.repository.PostRepository;
import com.woobeee.mvc.blog.repository.PostTagRepository;
import com.woobeee.mvc.blog.repository.TagRepository;
import com.woobeee.mvc.blog.support.ProgressInputStream;
import com.woobeee.mvc.blog.support.RedisSupport;
import com.woobeee.mvc._common.storage.PresignedUrlFactory;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final TagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final AuthMemberResolver authMemberResolver;

    private final RedisSupport redisSupport;
    private final S3Client s3Client;
    private final StorageProperties storageProperties;
    private final PresignedUrlFactory presignedUrlFactory;

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

        List<String> tags = TagNormalizer.normalize(request.tags());
        post.updateDescription(request.descriptionKo(), request.descriptionEn());
        post = postRepository.save(post);
        linkTags(post.getId(), tags);

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

        List<String> tags = TagNormalizer.normalize(request.tags());
        post.updateContent(
                request.titleKo(),
                request.titleEn(),
                readMarkdown(markdownKr),
                readMarkdown(markdownEn),
                request.categoryId()
        );
        post.updateDescription(request.descriptionKo(), request.descriptionEn());
        // BLOG-AC-19 — 집합 교체
        postTagRepository.deleteAllForPost(post.getId());
        linkTags(post.getId(), tags);

        uploadAttachments(post.getId(), files);
        postRepository.save(post);
    }

    /**
     * BLOG-AC-19 — 이름을 소문자로 비교해 있는 태그는 재사용, 없는 이름은 새로 만들고(표기는 입력 그대로)
     * 글-태그 연결을 만든다. 연결 순서는 입력 순서다.
     */
    private void linkTags(Long postId, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        List<String> lower = names.stream().map(String::toLowerCase).toList();
        Map<String, Tags> byLower = new HashMap<>();
        for (Tags t : tagRepository.findAllByLowerNames(lower)) {
            byLower.put(t.getName().toLowerCase(), t);
        }
        List<Tags> missing = new ArrayList<>();
        for (String name : names) {
            if (!byLower.containsKey(name.toLowerCase())) {
                missing.add(Tags.create(name));
            }
        }
        if (!missing.isEmpty()) {
            for (Tags t : tagRepository.saveAll(missing)) {
                byLower.put(t.getName().toLowerCase(), t);
            }
        }
        List<PostTags> links = new ArrayList<>();
        for (String name : names) {
            links.add(PostTags.create(postId, byLower.get(name.toLowerCase()).getId()));
        }
        postTagRepository.saveAll(links);
    }

    /** BLOG-AC-20 — 글 id 를 모아 한 번에. 글이 없으면 조회하지 않는다. */
    private Map<Long, List<TagResponse>> tagsByPost(List<Long> postIds) {
        Map<Long, List<TagResponse>> out = new HashMap<>();
        if (postIds.isEmpty()) {
            return out;
        }
        for (TagRepository.PostTagRow row : tagRepository.findAllForPosts(postIds)) {
            out.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(new TagResponse(row.getTagId(), row.getName()));
        }
        return out;
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

        // FK 가 없으므로 연결을 먼저 지운다 (BLOG-AC-19)
        postTagRepository.deleteAllForPost(post.getId());
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
                descriptionFor(post, locale),
                content,
                categoryName,
                post.getCategoryId(),
                redisAfter,
                likeCount,
                isLiked,
                post.getCreatedAt(),
                tagsByPost(List.of(post.getId())).getOrDefault(post.getId(), List.of())
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

            String publicUrl = publicUrl(postId, fileName);

            matcher.appendReplacement(result, Matcher.quoteReplacement(publicUrl));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 본문에 박히는 이미지 주소. presigned URL 이고, <b>같은 시간대의 모든 방문자가 같은 값</b>을
     * 받는다({@link PresignedUrlFactory} 가 서명 시각을 시간 단위로 내린다). 그래야 CDN 이
     * 캐시한다 -- 서명이 요청마다 달라지면 방문자마다 다른 오브젝트가 되어 원점까지 다 내려온다.
     *
     * <p>basename 만 남긴다. 본문의 {@code ${..}} 가 경로 성분을 담고 있으면 같은 버킷의 다른
     * prefix 를 여는 유효한 서명이 만들어진다 -- 버킷이 비공개여도 서명이 접근을 허가하므로
     * 막히지 않는다.
     */
    private String publicUrl(Long postId, String fileName) {
        String safeName = Paths.get(fileName).getFileName().toString().trim();
        return presignedUrlFactory.getUrl(postId + "/" + safeName);
    }

    @Override
    @Transactional(readOnly = true)
    public GetPostsResponse getAllPost(String q, String locale, Long categoryId, String tag, Pageable pageable) {
        Page<Posts> posts;
        List<Long> categories = categoryId == null ? null : findAllChildIdsIncludingSelf(categoryId);
        posts = postRepository.searchPosts(categories, q, locale, tag, pageable);
        Map<Long, List<TagResponse>> tags = tagsByPost(posts.getContent().stream().map(Posts::getId).toList());

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
                    descriptionFor(post, locale),
                    content,
                    categoryName,
                    post.getCategoryId(),
                    redisAfter,
                    likeCount,
                    post.getCreatedAt(),
                    tags.getOrDefault(post.getId(), List.of())
            );
        }).toList();

        return new GetPostsResponse(posts.hasNext(), contents);
    }

    /** BLOG-AC-23 — 영어 설명이 없으면 한국어로 대체한다(제목과 같은 규칙). 둘 다 없으면 null. */
    private static String descriptionFor(Posts post, String locale) {
        if (locale.equalsIgnoreCase("en") && post.getDescriptionEn() != null) {
            return post.getDescriptionEn();
        }
        return post.getDescriptionKo();
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
