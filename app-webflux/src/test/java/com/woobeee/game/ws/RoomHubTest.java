package com.woobeee.game.ws;

import org.junit.jupiter.api.Test;
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
}
