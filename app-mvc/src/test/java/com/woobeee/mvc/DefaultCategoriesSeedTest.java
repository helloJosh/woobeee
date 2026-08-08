package com.woobeee.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 마이그레이션이 실제로 운영 카테고리 분류를 심는지 실 Postgres 에 대고 확인한다.
 * {@link SchemaValidationTest} 와 같은 전제다 — docker compose 의 PostgreSQL(9432)이 떠 있어야
 * 통과한다. 여기서는 Flyway 를 켜 둔다: 테스트 자체가 마이그레이션을 적용하므로, 아직
 * 적용하지 않은 로컬 DB 에서도 자급자족으로 돈다(Flyway 는 적용된 버전을 건너뛴다).
 *
 * <p>V3 는 자리표시(백엔드/프론트엔드/인프라/…)를 심었고 V5 가 그것을 구 프로젝트에서 옮겨온
 * 실제 분류로 교체한다. 이 테스트가 보는 것은 <b>교체 후의 상태</b>다 — V3 만 적용된 DB 는
 * 미완성이므로 여기서 실패하는 것이 맞다.
 */
@SpringJUnitConfig
// JPA/리포지토리 자동구성은 끈다 — 이 테스트는 JDBC 와 Flyway 만 필요하고, JPA 를 켜면
// 리포지토리 스캔이 QuerydslConfig 등 앱의 빈 그래프 전체를 요구한다.
@EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.flyway.enabled=true"
})
class DefaultCategoriesSeedTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 시드의 단일 출처는 V5__woobeee_categories.sql — 이 목록을 바꾸면 마이그레이션도 함께 바꾼다. */
    private static final List<String> TOP_LEVEL_NAMES = List.of("BACKEND", "FRONTEND");

    private static final List<String> ALL_NAMES =
            List.of("BACKEND", "FRONTEND", "Spring Batch", "Database", "Kafka", "NextJS");

    @Test
    void topLevelCategoriesAreSeededWithoutAParent() {
        List<String> seeded = jdbcTemplate.queryForList(
                "SELECT name_ko FROM categories WHERE name_ko = ANY(?) AND parent_id IS NULL",
                String.class,
                (Object) ALL_NAMES.toArray(String[]::new));

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_NAMES);
    }

    /**
     * 하위 카테고리는 부모 밑에 붙어야 한다. 이름만 맞고 parent_id 가 비면 블로그 화면에서
     * 최상위로 튀어나오므로, 이름과 부모를 한 쌍으로 고정한다.
     */
    @Test
    void childCategoriesHangUnderTheirParent() {
        List<String> pairs = jdbcTemplate.queryForList(
                "SELECT c.name_ko || ' < ' || p.name_ko FROM categories c"
                        + " JOIN categories p ON p.id = c.parent_id"
                        + " WHERE c.name_ko = ANY(?)",
                String.class,
                (Object) ALL_NAMES.toArray(String[]::new));

        assertThat(pairs).containsExactlyInAnyOrder(
                "Spring Batch < BACKEND",
                "Database < BACKEND",
                "Kafka < BACKEND",
                "NextJS < FRONTEND");
    }

    /**
     * 글이 붙어 있는 분류의 id 를 고정한다. posts.category_id 는 옮겨온 값 그대로라, V5 가
     * 이름만 갈아끼우고 id 를 어긋나게 두면 글이 조용히 엉뚱한 분류로 간다.
     */
    @Test
    void categoriesReferencedByPostsKeepTheirIds() {
        List<String> pairs = jdbcTemplate.queryForList(
                "SELECT id || '=' || name_ko FROM categories WHERE id IN (2, 5, 6)",
                String.class);

        assertThat(pairs).containsExactlyInAnyOrder(
                "2=Spring Batch",
                "5=Database",
                "6=Kafka");
    }

    /** 영문 이름도 함께 심는다 — 프론트가 name_en 을 읽는 화면에서 빈칸이 나오면 안 된다. */
    @Test
    void seededCategoriesCarryEnglishNames() {
        Integer missingEnglish = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories"
                        + " WHERE name_ko = ANY(?) AND (name_en IS NULL OR name_en = '')",
                Integer.class,
                (Object) ALL_NAMES.toArray(String[]::new));

        assertThat(missingEnglish).isZero();
    }
}
