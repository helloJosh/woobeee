package com.woobeee.game;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import java.time.Clock;

@Configuration
public class GameConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * spring-boot-starter-data-r2dbc auto-configures a {@link ReactiveTransactionManager}
     * (backed by the R2DBC {@code ConnectionFactory}); this wraps it into an operator so
     * services can scope a transaction around a chain of reactive DB calls.
     */
    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
