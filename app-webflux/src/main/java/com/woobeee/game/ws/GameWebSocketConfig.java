package com.woobeee.game.ws;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

@Configuration
public class GameWebSocketConfig {
    public static final Duration JOIN_DEADLINE = Duration.ofSeconds(10);
    public static final Duration DISCONNECT_GRACE = Duration.ofSeconds(30);

    @Bean
    public Scheduler gameTimerScheduler() {
        return Schedulers.parallel();
    }

    @Bean
    public GameWebSocketHandler gameWebSocketHandler(
            JoinAuthenticator joinAuthenticator,
            RoomCommandDispatcher dispatcher,
            RoomHub roomHub,
            ObjectMapper objectMapper,
            Scheduler gameTimerScheduler
    ) {
        return new GameWebSocketHandler(
                joinAuthenticator,
                dispatcher,
                roomHub,
                objectMapper,
                JOIN_DEADLINE,
                DISCONNECT_GRACE,
                gameTimerScheduler
        );
    }

    @Bean
    public HandlerMapping gameWebSocketHandlerMapping(GameWebSocketHandler gameWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.<String, WebSocketHandler>of("/ws/game", gameWebSocketHandler));
        mapping.setOrder(-1);
        return mapping;
    }
}
