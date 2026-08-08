package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Likes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LikeRepository extends JpaRepository<Likes, Long> {
    Long countByPostId(Long postId);

    boolean existsByMemberIdAndPostId(Long memberId, Long postId);

    Optional<Likes> findByMemberIdAndPostId(Long memberId, Long postId);
}
