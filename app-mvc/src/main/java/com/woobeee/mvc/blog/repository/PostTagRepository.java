package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.PostTags;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostTagRepository extends JpaRepository<PostTags, Long> {

    /** 집합 교체와 글 삭제 캐스케이드가 쓴다 (BLOG-AC-19). */
    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM post_tags WHERE post_id = :postId", nativeQuery = true)
    void deleteAllForPost(@Param("postId") Long postId);
}
