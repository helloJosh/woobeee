package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Comments;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comments, Long> {
    List<Comments> findAllByPostId(Long postId);
}
