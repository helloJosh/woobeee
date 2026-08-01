package com.woobeee.game;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class GameConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
