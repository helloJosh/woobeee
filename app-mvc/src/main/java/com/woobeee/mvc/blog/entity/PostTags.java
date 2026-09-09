package com.woobeee.mvc.blog.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 글-태그 연결 (BLOG-AC-19). FK 없음 — 글 삭제 시 서비스가 먼저 지운다. */
@Getter
@Entity
@Table(name = "post_tags")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostTags {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long postId;

    @Column(nullable = false)
    private Long tagId;

    private PostTags(Long postId, Long tagId) {
        this.postId = postId;
        this.tagId = tagId;
    }

    public static PostTags create(Long postId, Long tagId) {
        return new PostTags(postId, tagId);
    }
}
