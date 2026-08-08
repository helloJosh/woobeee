package com.woobeee.game.ws;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

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

        assertThat(overflowOccurred)
                .as("Buffer overflow should be detected within 300 broadcasts to a room with no consumer")
                .isTrue();
    }

    /**
     * I2 — {@code Sinks.many().multicast().onBackpressureBuffer(...)} 는 그 자체로 직렬화되지
     * 않는다. 서로 다른 스레드(방 안의 서로 다른 플레이어를 처리하는 서로 다른 Netty 이벤트 루프를
     * 흉내낸다)가 동시에 같은 방에 emit 하면 그중 하나가 {@code FAIL_NON_SERIALIZED} 를 받고,
     * 고치기 전의 {@code broadcast} 는 그 결과를 로그만 남기고 메시지를 그냥 버렸다 — 유효한
     * {@code OMOK_MOVED} 가 상대의 {@code OMOK_REJECTED} 와 경합하다 조용히 사라질 수 있었다.
     * 이 테스트는 그 경합을 여러 스레드로 실제로 만들어, 보낸 메시지 수와 받은 메시지 수가
     * 정확히 같은지를 확인한다. 고치기 전 코드에서는 이 assertion 이 간헐적으로(그러나 이 정도
     * 스레드/횟수면 사실상 매번) 실패한다.
     */
    @Test
    void concurrentBroadcastsToOneRoomDoNotDropMessages() throws InterruptedException {
        RoomHub hub = new RoomHub();
        String roomId = "concurrent-room";
        int threadCount = 8;
        int messagesPerThread = 20;
        int total = threadCount * messagesPerThread;

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch allReceived = new CountDownLatch(total);
        hub.subscribe(roomId).subscribe(message -> {
            received.add(message.type());
            allReceived.countDown();
        });

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int t = 0; t < threadCount; t++) {
                int threadIndex = t;
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < messagesPerThread; i++) {
                            hub.broadcast(roomId, ServerMessage.of(
                                    "OMOK_MOVED", Map.of("thread", threadIndex, "seq", i)));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            start.countDown();
            boolean broadcastersDone = finished.await(10, TimeUnit.SECONDS);
            assertThat(broadcastersDone).as("all broadcaster threads must finish").isTrue();

            boolean allDelivered = allReceived.await(5, TimeUnit.SECONDS);
            assertThat(allDelivered)
                    .as("expected all %d broadcasts to be delivered, only %d arrived",
                            total, received.size())
                    .isTrue();
            assertThat(received).hasSize(total);
        } finally {
            pool.shutdownNow();
        }
    }
}
