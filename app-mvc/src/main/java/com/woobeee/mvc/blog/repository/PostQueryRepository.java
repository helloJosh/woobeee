package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Posts;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

public interface PostQueryRepository {
    List<PostRepository.CategoryCount> countGroupByCategoryId(Collection<Long> categoryIds);

    /**
     * 목록/검색 (BLOG-AC-01~03, 21). 조건은 전부 AND: 카테고리 id 집합(null/빈 = 전체), 검색어(locale 의
     * 제목·본문 컬럼 부분일치, 대소문자 무시), 태그 이름(대소문자 무시). 정렬은 created_at DESC, id DESC.
     */
    Page<Posts> searchPosts(List<Long> categoryIds, String q, String locale, String tag, Pageable pageable);
}
