package com.woobeee.mvc;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(initializers = ConfigDataApplicationContextInitializer.class)
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@ActiveProfiles("test")
class SchemaValidationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    void schemaMatchesJpaMappings() {
        assertThat(entityManagerFactory).isNotNull();
    }
}
