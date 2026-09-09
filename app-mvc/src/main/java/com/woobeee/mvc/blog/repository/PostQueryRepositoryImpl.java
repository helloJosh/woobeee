package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Posts;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 목록·집계 쿼리. QueryDSL 잔존 구현을 네이티브 SQL 로 옮겼다(CLAUDE.md 쿼리 규칙) — 태그 조건을
 * 덧붙이는 시점에 함께 전환했다. 값은 전부 바인딩 파라미터로만 들어간다.
 */
public class PostQueryRepositoryImpl implements PostQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<PostRepository.CategoryCount> countGroupByCategoryId(Collection<Long> categoryIds) {
        if (CollectionUtils.isEmpty(categoryIds)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT category_id, COUNT(*) FROM posts
                WHERE category_id IN (:categoryIds)
                GROUP BY category_id
                """)
                .setParameter("categoryIds", categoryIds)
                .getResultList();

        List<PostRepository.CategoryCount> out = new ArrayList<>();
        for (Object[] row : rows) {
            out.add(new PostRepository.CategoryCountResult(((Number) row[0]).longValue(), ((Number) row[1]).longValue()));
        }
        return out;
    }

    @Override
    public Page<Posts> searchPosts(List<Long> categoryIds, String q, String locale, String tag, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (!CollectionUtils.isEmpty(categoryIds)) {
            where.append(" AND p.category_id IN (:categoryIds)");
            params.put("categoryIds", categoryIds);
        }
        if (StringUtils.hasText(q)) {
            // BLOG-AC-02 — locale 의 컬럼만 본다
            boolean en = "en".equalsIgnoreCase(locale);
            where.append(en
                    ? " AND (p.title_en ILIKE :keyword OR p.text_en ILIKE :keyword)"
                    : " AND (p.title_ko ILIKE :keyword OR p.text_ko ILIKE :keyword)");
            params.put("keyword", "%" + escapeLike(q.trim()) + "%");
        }
        if (StringUtils.hasText(tag)) {
            // BLOG-AC-21 — 태그 이름은 대소문자 무시
            where.append(" AND EXISTS (SELECT 1 FROM post_tags pt JOIN tags t ON t.id = pt.tag_id"
                    + " WHERE pt.post_id = p.id AND lower(t.name) = lower(:tag))");
            params.put("tag", tag.trim());
        }

        Query select = entityManager.createNativeQuery(
                "SELECT p.* FROM posts p" + where + " ORDER BY p.created_at DESC, p.id DESC", Posts.class);
        Query count = entityManager.createNativeQuery("SELECT COUNT(*) FROM posts p" + where);
        params.forEach((k, v) -> {
            select.setParameter(k, v);
            count.setParameter(k, v);
        });
        if (pageable.isPaged()) {
            select.setFirstResult((int) pageable.getOffset());
            select.setMaxResults(pageable.getPageSize());
        }

        @SuppressWarnings("unchecked")
        List<Posts> contents = select.getResultList();
        long total = ((Number) count.getSingleResult()).longValue();
        return new PageImpl<>(contents, pageable, total);
    }

    /** ILIKE 의 와일드카드가 검색어 안에 있으면 문자 그대로 찾도록 이스케이프한다(기본 이스케이프 문자 '\'). */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
