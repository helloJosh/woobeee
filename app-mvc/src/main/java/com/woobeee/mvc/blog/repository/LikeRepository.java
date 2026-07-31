package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Likes;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LikeRepository extends JpaRepository<Likes, Long> {
    Long countByPostId(Long postId);

    boolean existsByMemberIdAndMemberRoleAndPostId(Long memberId, String memberRole, Long postId);

    Optional<Likes> findByMemberIdAndMemberRoleAndPostId(Long memberId, String memberRole, Long postId);
}
