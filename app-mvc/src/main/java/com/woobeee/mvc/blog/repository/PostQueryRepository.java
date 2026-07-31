package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Posts;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PostQueryRepository {
    List<PostRepository.CategoryCount> countGroupByCategoryId(Collection<Long> categoryIds);

    Page<Posts> searchPosts(List<Long> categoryIds, String q, String locale, Pageable pageable);
}
