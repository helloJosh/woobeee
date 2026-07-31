package com.woobeee.mvc.blog.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.entity.QPosts;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class PostQueryRepositoryImpl implements PostQueryRepository {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<PostRepository.CategoryCount> countGroupByCategoryId(Collection<Long> categoryIds) {
        if (CollectionUtils.isEmpty(categoryIds)) {
            return List.of();
        }

        QPosts post = QPosts.posts;
        List<Tuple> rows = queryFactory
                .select(post.categoryId, post.count())
                .from(post)
                .where(post.categoryId.in(categoryIds))
                .groupBy(post.categoryId)
                .fetch();

        return rows.stream()
                .map(row -> new PostRepository.CategoryCountResult(
                        row.get(post.categoryId),
                        row.get(post.count()) == null ? 0 : row.get(post.count())
                ))
                .map(PostRepository.CategoryCount.class::cast)
                .toList();
    }

    @Override
    public Page<Posts> searchPosts(List<Long> categoryIds, String q, String locale, Pageable pageable) {
        QPosts post = QPosts.posts;
        BooleanBuilder conditions = postSearchConditions(categoryIds, q, locale);

        List<Posts> contents = queryFactory
                .selectFrom(post)
                .where(conditions)
                .orderBy(post.createdAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(post.count())
                .from(post)
                .where(conditions)
                .fetchOne();

        return new PageImpl<>(contents, pageable, total == null ? 0 : total);
    }

    private BooleanBuilder postSearchConditions(List<Long> categoryIds, String q, String locale) {
        QPosts post = QPosts.posts;
        BooleanBuilder conditions = new BooleanBuilder();
        if (!CollectionUtils.isEmpty(categoryIds)) {
            conditions.and(post.categoryId.in(categoryIds));
        }
        if (StringUtils.hasText(q)) {
            String keyword = q.trim();
            if ("en".equalsIgnoreCase(locale)) {
                conditions.and(post.titleEn.containsIgnoreCase(keyword)
                        .or(post.textEn.containsIgnoreCase(keyword)));
            } else {
                conditions.and(post.titleKo.containsIgnoreCase(keyword)
                        .or(post.textKo.containsIgnoreCase(keyword)));
            }
        }

        return conditions;
    }
}
