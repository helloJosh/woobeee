package com.woobeee.game.room;

import com.woobeee.game.ws.RoomHub;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Component
public class RoomSweeper {
    private static final Logger log = LoggerFactory.getLogger(RoomSweeper.class);
    private static final Duration INTERVAL = Duration.ofMinutes(10);

    private final RoomRegistry roomRegistry;
    private final RoomHub roomHub;
    private final Clock clock;

    private Disposable subscription;

    public RoomSweeper(RoomRegistry roomRegistry, RoomHub roomHub, Clock clock) {
        this.roomRegistry = roomRegistry;
        this.roomHub = roomHub;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(INTERVAL, INTERVAL)
                .doOnNext(tick -> sweep())
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    public void sweep() {
        List<String> expired = roomRegistry.expiredRoomIds(clock.instant());
        expired.forEach(roomHub::close);
        int removed = roomRegistry.sweepExpired(clock.instant());
        if (removed > 0) {
            log.info("Swept {} expired game rooms", removed);
        }
    }
}
