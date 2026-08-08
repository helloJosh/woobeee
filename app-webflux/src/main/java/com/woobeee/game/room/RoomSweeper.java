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
import java.time.Instant;
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
                .subscribe(
                        tick -> onTick(),
                        error -> log.error("Room sweeper interval terminated unexpectedly", error));
    }

    /**
     * Called on every interval tick. A throwing sweep must not reach the {@code Flux.interval}
     * subscription -- an {@code onError} there ends the sequence for good, and nothing
     * resubscribes, so TTL reclamation would be silently dead until the process restarts.
     */
    void onTick() {
        try {
            sweep();
        } catch (Exception e) {
            log.error("Room sweep failed; will retry on the next interval", e);
        }
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    public void sweep() {
        Instant now = clock.instant();
        List<String> expired = roomRegistry.expiredRoomIds(now);
        expired.forEach(roomHub::close);
        int removed = roomRegistry.sweepExpired(now);
        if (removed > 0) {
            log.info("Swept {} expired game rooms", removed);
        }
    }
}
