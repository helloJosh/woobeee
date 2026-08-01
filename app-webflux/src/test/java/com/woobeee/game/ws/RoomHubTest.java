package com.woobeee.game.ws;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

class RoomHubTest {

    @Test
    void subscribersOfTheSameRoomReceiveBroadcasts() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> {
                    hub.broadcast("room-1", ServerMessage.of("ROOM_STATE", Map.of("n", 1)));
                    hub.broadcast("room-1", ServerMessage.of("ROOM_STATE", Map.of("n", 2)));
                })
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .verifyComplete();
    }

    @Test
    void broadcastsDoNotLeakAcrossRooms() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> {
                    hub.broadcast("room-2", ServerMessage.of("OTHER", Map.of()));
                    hub.broadcast("room-1", ServerMessage.of("MINE", Map.of()));
                })
                .expectNextMatches(message -> message.type().equals("MINE"))
                .verifyComplete();
    }

    @Test
    void closingARoomCompletesItsSubscribers() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1"))
                .then(() -> hub.close("room-1"))
                .verifyComplete();
    }

    @Test
    void broadcastToARoomWithNoSubscribersIsANoop() {
        RoomHub hub = new RoomHub();

        hub.broadcast("ghost", ServerMessage.of("ROOM_STATE", Map.of()));

        StepVerifier.create(hub.subscribe("ghost").take(Duration.ofMillis(50)))
                .verifyComplete();
    }

    @Test
    void broadcastFailureWhenBufferOverflows() {
        RoomHub hub = new RoomHub();

        // Trigger the sink creation and subscribe with a subscriber that doesn't request items
        // This applies backpressure and causes the buffer to fill
        hub.subscribe("overflow-room").subscribe(
                item -> {}, // onNext (won't be called due to backpressure)
                err -> {}, // onError
                () -> {}, // onComplete
                subscription -> {
                    // Request 0 items to apply backpressure and fill the buffer
                    // subscription.request(0) is implicit when we don't call request
                }
        );

        // Fill the buffer (size 256) and beyond
        boolean overflowOccurred = false;
        for (int i = 0; i < 300; i++) {
            Sinks.EmitResult result = hub.broadcast("overflow-room",
                    ServerMessage.of("FILL", Map.of("seq", i)));
            if (result != Sinks.EmitResult.OK) {
                overflowOccurred = true;
                break;
            }
        }

        assert overflowOccurred : "Expected overflow but buffer never filled";
    }
}
