package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Tags;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface TagRepository extends JpaRepository<Tags, Long> {

    /** 글 id 순, 같은 글 안에서는 이름 순 — 목록 조립용 배치 조회 (BLOG-AC-20). */
    interface PostTagRow {
        Long getPostId();
        Long getTagId();
        String getName();
    }

    record PostTagRowResult(Long postId, Long tagId, String name) implements PostTagRow {
        @Override public Long getPostId() { return postId; }
        @Override public Long getTagId() { return tagId; }
        @Override public String getName() { return name; }
    }

    /** 인기 태그 한 줄 (BLOG-AC-22). */
    interface TagCount {
        Long getId();
        String getName();
        long getCnt();
    }

    /** 이름은 소문자로 비교한다 — 호출부가 lower 로 내려 넘긴다 (BLOG-AC-19). */
    @Query(value = "SELECT * FROM tags WHERE lower(name) IN (:lowerNames)", nativeQuery = true)
    List<Tags> findAllByLowerNames(@Param("lowerNames") Collection<String> lowerNames);

    @Query(value = """
            SELECT pt.post_id AS "postId", t.id AS "tagId", t.name AS "name"
            FROM post_tags pt
            JOIN tags t ON t.id = pt.tag_id
            WHERE pt.post_id IN (:postIds)
            ORDER BY pt.post_id, t.name
            """, nativeQuery = true)
    List<PostTagRow> findAllForPosts(@Param("postIds") Collection<Long> postIds);

    /** 글 수 내림차순, 같으면 이름 오름차순. 글 없는 태그는 조인에서 빠진다 (BLOG-AC-22). */
    @Query(value = """
            SELECT t.id AS "id", t.name AS "name", COUNT(pt.id) AS "cnt"
            FROM tags t
            JOIN post_tags pt ON pt.tag_id = t.id
            GROUP BY t.id, t.name
            ORDER BY COUNT(pt.id) DESC, t.name ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<TagCount> findPopular(@Param("limit") int limit);
}
