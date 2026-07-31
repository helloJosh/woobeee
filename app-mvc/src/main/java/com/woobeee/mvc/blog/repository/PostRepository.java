package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Posts;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PostRepository extends JpaRepository<Posts, Long>, PostQueryRepository {

    interface CategoryCount {
        Long getCategoryId();
        long getCnt();
    }

    record CategoryCountResult(Long categoryId, long cnt) implements CategoryCount {
        @Override
        public Long getCategoryId() {
            return categoryId;
        }

        @Override
        public long getCnt() {
            return cnt;
        }
    }

    void deleteAllByCategoryIdIn(List<Long> ids);
}
