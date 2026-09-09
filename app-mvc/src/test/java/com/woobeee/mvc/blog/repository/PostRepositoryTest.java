package com.woobeee.mvc.blog.repository;

import com.woobeee.mvc.blog.entity.Categories;
import com.woobeee.mvc.blog.entity.PostTags;
import com.woobeee.mvc.blog.entity.Posts;
import com.woobeee.mvc.blog.entity.Tags;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 목록 쿼리(네이티브 SQL)와 태그 저장소를 실 Postgres 에 대고 고정한다. QueryDSL 시절에는 테스트가
 * 없어 BLOG-AC-01~04 가 문서로만 있었다 — 네이티브 전환과 함께 여기서 처음 고정한다.
 * 개발 DB 에 다른 글이 있어도 흔들리지 않도록 매 테스트가 자기 카테고리 안에서만 조회한다.
 */
@SpringJUnitConfig
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.blog.repository")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
class PostRepositoryTest {

    @Autowired PostRepository postRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired TagRepository tagRepository;
    @Autowired PostTagRepository postTagRepository;

    private Categories category(String name) {
        return categoryRepository.save(new Categories(name, name + "-en", null));
    }

    private Posts post(Categories c, String titleKo, String titleEn, String textKo, String textEn) {
        return postRepository.saveAndFlush(new Posts(titleKo, titleEn, textKo, textEn, c.getId(), 1L));
    }

    private Tags tag(String name) {
        return tagRepository.save(Tags.create(name));
    }

    private void link(Posts p, Tags t) {
        postTagRepository.save(PostTags.create(p.getId(), t.getId()));
    }

    /** BLOG-AC-01 — 최신 글이 먼저, 같은 시각이면 id 큰 쪽이 먼저. */
    @Test
    void postsAreOrderedNewestFirstThenByIdDescending() {
        Categories c = category("order");
        Posts first = post(c, "a", "a", "", "");
        Posts second = post(c, "b", "b", "", "");
        Posts third = post(c, "c", "c", "", "");

        Page<Posts> page = postRepository.searchPosts(List.of(c.getId()), null, "ko", null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Posts::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    /** BLOG-AC-01 — 페이징: 두 번째 페이지와 hasNext. */
    @Test
    void pagingSlicesTheOrderedListAndReportsHasNext() {
        Categories c = category("paging");
        for (int i = 0; i < 5; i++) post(c, "p" + i, "p" + i, "", "");

        Page<Posts> firstPage = postRepository.searchPosts(List.of(c.getId()), null, "ko", null, PageRequest.of(0, 2));
        Page<Posts> lastPage = postRepository.searchPosts(List.of(c.getId()), null, "ko", null, PageRequest.of(2, 2));

        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.hasNext()).isFalse();
    }

    /** BLOG-AC-02 — locale=en 은 영어 컬럼만, 그 외는 한국어 컬럼만 대소문자 무시 부분일치로 찾는다. */
    @Test
    void searchTargetsTheColumnsOfTheRequestedLocale() {
        Categories c = category("locale");
        Posts koOnly = post(c, "카프카 입문", "Intro", "본문", "body");
        Posts enOnly = post(c, "소개", "Kafka basics", "본문", "streams body");
        Posts inBody = post(c, "기타", "misc", "여기 카프카 나옴", "nothing");

        assertThat(postRepository.searchPosts(List.of(c.getId()), "카프카", "ko", null, PageRequest.of(0, 10)).getContent())
                .extracting(Posts::getId).containsExactlyInAnyOrder(koOnly.getId(), inBody.getId());
        assertThat(postRepository.searchPosts(List.of(c.getId()), "KAFKA", "en", null, PageRequest.of(0, 10)).getContent())
                .extracting(Posts::getId).containsExactly(enOnly.getId());
        assertThat(postRepository.searchPosts(List.of(c.getId()), "  ", "ko", null, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(3);
    }

    /** BLOG-AC-03 — 카테고리 id 집합이 있으면 그 안의 글만, null 이면 전부. */
    @Test
    void aCategoryIdSetRestrictsTheListWithIn() {
        Categories a = category("cat-a");
        Categories b = category("cat-b");
        Categories other = category("cat-other");
        Posts inA = post(a, "a", "a", "", "");
        Posts inB = post(b, "b", "b", "", "");
        Posts elsewhere = post(other, "o", "o", "", "");

        Page<Posts> page = postRepository.searchPosts(List.of(a.getId(), b.getId()), null, "ko", null, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Posts::getId).containsExactlyInAnyOrder(inA.getId(), inB.getId());
        assertThat(postRepository.searchPosts(null, null, "ko", null, PageRequest.of(0, 100)).getContent())
                .extracting(Posts::getId).contains(inA.getId(), inB.getId(), elsewhere.getId());
    }

    /** BLOG-AC-04 — 카테고리별 글 수를 GROUP BY 한 번으로. 글 없는 카테고리는 행이 없다. */
    @Test
    void categoryCountsComeFromOneGroupBy() {
        Categories two = category("two");
        Categories one = category("one");
        Categories none = category("none");
        post(two, "x", "x", "", ""); post(two, "y", "y", "", ""); post(one, "z", "z", "", "");

        List<PostRepository.CategoryCount> counts =
                postRepository.countGroupByCategoryId(List.of(two.getId(), one.getId(), none.getId()));

        assertThat(counts).extracting(PostRepository.CategoryCount::getCategoryId, PostRepository.CategoryCount::getCnt)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(two.getId(), 2L),
                        org.assertj.core.groups.Tuple.tuple(one.getId(), 1L));
        assertThat(postRepository.countGroupByCategoryId(List.of())).isEmpty();
    }

    /** BLOG-AC-21 — tag 필터는 대소문자를 무시하고 카테고리·검색과 AND 로 겹친다. */
    @Test
    void theTagFilterIsCaseInsensitiveAndCombinesWithTheOtherConditions() {
        Categories c = category("tagged");
        Tags spring = tag("Spring");
        Tags kafka = tag("Kafka");
        Posts both = post(c, "둘 다", "both", "", "");
        Posts onlySpring = post(c, "스프링만", "spring only", "", "");
        Posts untagged = post(c, "없음", "none", "", "");
        link(both, spring); link(both, kafka); link(onlySpring, spring);

        assertThat(postRepository.searchPosts(List.of(c.getId()), null, "ko", "spring", PageRequest.of(0, 10)).getContent())
                .extracting(Posts::getId).containsExactlyInAnyOrder(both.getId(), onlySpring.getId());
        assertThat(postRepository.searchPosts(List.of(c.getId()), "둘", "ko", "KAFKA", PageRequest.of(0, 10)).getContent())
                .extracting(Posts::getId).containsExactly(both.getId());
        assertThat(postRepository.searchPosts(List.of(c.getId()), null, "ko", "nope", PageRequest.of(0, 10)).getTotalElements())
                .isZero();
        assertThat(postRepository.searchPosts(List.of(c.getId()), null, "ko", null, PageRequest.of(0, 10)).getContent())
                .extracting(Posts::getId).contains(untagged.getId());
    }

    /** BLOG-AC-20 — 글 id 묶음으로 태그를 한 번에: 글 id 순, 같은 글 안에서는 이름 순. */
    @Test
    void tagsForManyPostsComeBackInOneQueryOrderedByPostThenName() {
        Categories c = category("batch");
        Tags b = tag("beta");
        Tags a = tag("alpha");
        Posts p1 = post(c, "1", "1", "", "");
        Posts p2 = post(c, "2", "2", "", "");
        Posts p3 = post(c, "3", "3", "", "");
        link(p1, b); link(p1, a); link(p2, a);

        List<TagRepository.PostTagRow> rows = tagRepository.findAllForPosts(List.of(p1.getId(), p2.getId(), p3.getId()));

        assertThat(rows).extracting(TagRepository.PostTagRow::getPostId, TagRepository.PostTagRow::getName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(p1.getId(), "alpha"),
                        org.assertj.core.groups.Tuple.tuple(p1.getId(), "beta"),
                        org.assertj.core.groups.Tuple.tuple(p2.getId(), "alpha"));
    }

    /** BLOG-AC-19 — 이름은 소문자로 비교해 재사용하고 표기는 저장된 그대로다. 글 삭제 캐스케이드는 연결만 지운다. */
    @Test
    void tagsAreLookedUpCaseInsensitivelyAndLinksAreRemovedPerPost() {
        Tags stored = tag("GraphQL-" + System.nanoTime());
        Categories c = category("lookup");
        Posts p = post(c, "g", "g", "", "");
        link(p, stored);

        List<Tags> found = tagRepository.findAllByLowerNames(List.of(stored.getName().toLowerCase()));
        assertThat(found).extracting(Tags::getName).containsExactly(stored.getName());

        postTagRepository.deleteAllForPost(p.getId());
        assertThat(tagRepository.findAllForPosts(List.of(p.getId()))).isEmpty();
        assertThat(tagRepository.findById(stored.getId())).isPresent();
    }

    /** BLOG-AC-22 — 인기 태그: 글 수 내림차순, 같으면 이름 오름차순, 글 없는 태그는 제외, limit 적용. */
    @Test
    void popularTagsAreOrderedByCountThenNameAndSkipUnusedTags() {
        String suffix = "-" + System.nanoTime();
        Categories c = category("popular");
        Tags hot = tag("zz-hot" + suffix);
        Tags warm1 = tag("bb-warm" + suffix);
        Tags warm2 = tag("aa-warm" + suffix);
        Tags unused = tag("unused" + suffix);
        Posts p1 = post(c, "1", "1", "", ""); Posts p2 = post(c, "2", "2", "", ""); Posts p3 = post(c, "3", "3", "", "");
        link(p1, hot); link(p2, hot); link(p3, hot);
        link(p1, warm1); link(p2, warm1);
        link(p1, warm2); link(p2, warm2);

        List<TagRepository.TagCount> top = tagRepository.findPopular(3);

        assertThat(top).extracting(TagRepository.TagCount::getName)
                .containsExactly(hot.getName(), warm2.getName(), warm1.getName());
        assertThat(top.get(0).getCnt()).isEqualTo(3L);
        assertThat(tagRepository.findPopular(100)).extracting(TagRepository.TagCount::getName)
                .doesNotContain(unused.getName());
        assertThat(tagRepository.findPopular(1)).hasSize(1);
    }
}
