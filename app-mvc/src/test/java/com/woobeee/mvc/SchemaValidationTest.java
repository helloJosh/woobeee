package com.woobeee.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import com.woobeee.mvc._common.config.QuerydslConfig;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@Import(QuerydslConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:9432/market",
        "spring.datasource.username=root",
        "spring.datasource.password=123456789",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.type=org.apache.commons.dbcp2.BasicDataSource",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=validate",
        "spring.flyway.enabled=false"
})
class SchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void schemaMatchesJpaMappings() {
        assertThat(entityManagerFactory).isNotNull();
    }
}
