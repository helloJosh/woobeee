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
 * V3__default_categories.sql 이 실제로 기본 카테고리를 심는지 실 Postgres 에 대고 확인한다.
 * {@link SchemaValidationTest} 와 같은 전제다 — docker compose 의 PostgreSQL(9432)이 떠 있어야
 * 통과한다. 여기서는 Flyway 를 켜 둔다: 테스트 자체가 마이그레이션을 적용하므로, 아직 V3 를
 * 적용하지 않은 로컬 DB 에서도 자급자족으로 돈다(Flyway 는 적용된 버전을 건너뛴다).
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

    /** 시드의 단일 출처는 V3__default_categories.sql — 이 목록을 바꾸면 마이그레이션도 함께 바꾼다. */
    private static final List<String> DEFAULT_KO_NAMES =
            List.of("백엔드", "프론트엔드", "인프라", "알고리즘", "회고", "기타");

    @Test
    void defaultCategoriesAreSeededAsTopLevelEntries() {
        List<String> seeded = jdbcTemplate.queryForList(
                "SELECT name_ko FROM categories WHERE name_ko = ANY(?) AND parent_id IS NULL",
                String.class,
                (Object) DEFAULT_KO_NAMES.toArray(String[]::new));

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(DEFAULT_KO_NAMES);
    }

    /** 영문 이름도 함께 심는다 — 프론트가 name_en 을 읽는 화면에서 빈칸이 나오면 안 된다. */
    @Test
    void defaultCategoriesCarryEnglishNames() {
        Integer missingEnglish = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM categories"
                        + " WHERE name_ko = ANY(?) AND (name_en IS NULL OR name_en = '')",
                Integer.class,
                (Object) DEFAULT_KO_NAMES.toArray(String[]::new));

        assertThat(missingEnglish).isZero();
    }
}
