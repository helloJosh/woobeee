package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import com.woobeee.mvc.blog.api.response.GetPostsResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface PostService {
    void savePost (
            PostPostRequest request,
            String loginId,
            MultipartFile markdownEn,
            MultipartFile markdownKr,
            List<MultipartFile> files
    );

    void updatePost(
            Long postId,
            PostPostRequest request,
            String loginId,
            MultipartFile markdownEn,
            MultipartFile markdownKr,
            List<MultipartFile> files
    );

    void deletePost(Long postId, String loginId);
    GetPostsResponse getAllPost(String q, String locale, Long categoryId, Pageable pageable);
    GetPostResponse getPost(Long postId, String locale, String loginId, HttpServletRequest request);

    /**
     * 글 본문 이미지의 바이트를 읽어 온다. 버킷은 비공개로 두고 앱이 자격증명으로 대신 읽는다 --
     * 그래서 프록시 규칙이나 버킷 익명 읽기 같은 인프라 작업 없이 이미지가 열린다.
     */
    PostImage loadPostImage(Long postId, String fileName);

    /** 스트리밍할 오브젝트 한 개. contentType 은 업로드 때 저장된 값이다. */
    record PostImage(byte[] bytes, String contentType) {
    }
}
