package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostCategoryRequest;
import com.woobeee.mvc.blog.api.response.GetCategoryResponse;
import com.woobeee.mvc.blog.entity.Categories;
import com.woobeee.mvc.blog.repository.CategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카테고리 쓰기가 실제로 커밋되는지 실 Postgres 에 대고 확인한다.
 *
 * <p><b>이 클래스에 {@code @Transactional} 을 달면 안 된다.</b> 달면 테스트가 읽기·쓰기
 * 트랜잭션을 먼저 열고 서비스가 거기 올라타므로, 서비스 자신의 트랜잭션 설정이 무엇이든
 * 쓰기가 성공한다 — 정확히 여기서 잡으려는 결함이 가려진다. 그래서 트랜잭션 없이 부르고
 * 뒤에서 직접 치운다.
 *
 * <p>BLOG-AC-25.
 */
@SpringJUnitConfig(initializers = ConfigDataApplicationContextInitializer.class)
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.blog.repository")
// 패키지를 스캔하면 blog 서비스 전부가 딸려와 auth 리포지토리까지 요구한다. 대상만 등록한다.
@Import(CategoryServiceImpl.class)
@ActiveProfiles("test")
class CategoryServiceImplTest {

    private static final String ROOT = "__test__DATA ENGINEERING";
    private static final String CHILD = "__test__Spark";

    @Autowired CategoryService categoryService;
    @Autowired CategoryRepository categoryRepository;

    @AfterEach
    void removeWhatThisTestMade() {
        categoryRepository.findAll().stream()
                .filter(c -> c.getNameKo() != null && c.getNameKo().startsWith("__test__"))
                .forEach(categoryRepository::delete);
    }

    /**
     * BLOG-AC-25 — 최상위 카테고리 저장이 커밋된다.
     *
     * <p>{@code CategoryServiceImpl} 은 클래스에 {@code @Transactional(readOnly = true)} 가
     * 걸려 있다. 쓰기 메서드가 이를 덮지 않으면 PostgreSQL 이 읽기 전용 트랜잭션에서의 INSERT 를
     * 거부한다 — 화면에서는 "카테고리가 안 만들어진다" 로만 보인다.
     */
    @Test
    void savingATopLevelCategoryActuallyCommits() {
        categoryService.saveCategory(new PostCategoryRequest(ROOT, ROOT), null);

        List<Categories> found = categoryRepository.findAll().stream()
                .filter(c -> ROOT.equals(c.getNameKo()))
                .toList();

        assertThat(found).hasSize(1);
        assertThat(found.getFirst().getParentId()).isNull();
    }

    /**
     * BLOG-AC-25 — 하위 카테고리도 커밋되고 트리에 부모 밑으로 붙는다.
     */
    @Test
    void savingAChildCategoryCommitsAndAppearsUnderItsParent() {
        categoryService.saveCategory(new PostCategoryRequest(ROOT, ROOT), null);
        Long parentId = categoryRepository.findAll().stream()
                .filter(c -> ROOT.equals(c.getNameKo()))
                .findFirst()
                .orElseThrow()
                .getId();

        categoryService.saveCategory(new PostCategoryRequest(CHILD, CHILD), parentId);

        GetCategoryResponse root = categoryService.getCategoryList("ko").stream()
                .filter(c -> ROOT.equals(c.name()))
                .findFirst()
                .orElseThrow();

        assertThat(root.children()).extracting(GetCategoryResponse::name).contains(CHILD);
    }
}
