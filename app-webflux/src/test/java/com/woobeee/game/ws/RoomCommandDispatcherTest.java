package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.ConnectionState;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.ws.payload.RoomStatePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomCommandDispatcherTest {

    private static final GameParticipant HOST = GameParticipant.member(11L, "host");
    private static final GameParticipant GUEST = GameParticipant.guest("a", "손님");

    private RoomService roomService;
    private RoomHub hub;
    private RoomCommandDispatcher dispatcher;
    private Room room;

    @BeforeEach
    void setUp() {
        GameIdGenerator ids = new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-1";
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "g1";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
        RoomRegistry registry =
                new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        roomService = new RoomService(registry);
        hub = new RoomHub();
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of());
        room = roomService.create(GameType.OMOK, HOST);
    }

    @Test
    void joinBroadcastsRoomState() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.join("room-1", "code", GUEST))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("ROOM_STATE");
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void readyBroadcastsRoomState() {
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.ready("room-1", GUEST.participantId(), true))
                .assertNext(message -> {
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants().get(1).ready()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void disconnectBroadcastsTheDisconnectedState() {
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.disconnected("room-1", GUEST.participantId()))
                .assertNext(message -> {
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants().get(1).connection()).isEqualTo("DISCONNECTED");
                })
                .verifyComplete();

        assertThat(room.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.DISCONNECTED);
    }

    @Test
    void startWithNoRegisteredSinkStillFlipsRoomStatusAndAnnouncesGameStart() {
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .expectNextMatches(message -> message.type().equals("GAME_START"))
                .verifyComplete();
    }

    @Test
    void closingTheLastMemberClosesTheHub() {
        StepVerifier.create(hub.subscribe("room-1"))
                .then(() -> dispatcher.leaveNow("room-1", HOST.participantId()))
                .verifyComplete();
    }

    @Test
    void aFailedCommandEmitsErrorToTheHubInsteadOfThrowing() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .assertNext(message -> assertThat(message.type()).isEqualTo("ERROR"))
                .verifyComplete();
    }

    @Test
    void onParticipantGoneFiresOnceWhenAMemberLeavesAndOthersRemain() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);

        dispatcher.leaveNow("room-1", GUEST.participantId());

        assertThat(log).containsExactly("onParticipantGone:" + GUEST.participantId());
    }

    /** F1 regression: the terminal departure must notify the sink too, before the hub closes. */
    @Test
    void onParticipantGoneFiresForTheLastMemberToo() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));

        dispatcher.leaveNow("room-1", HOST.participantId());

        assertThat(log).containsExactly("onParticipantGone:" + HOST.participantId());
    }

    @Test
    void onParticipantGoneIsNotFiredForANonMember() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));

        dispatcher.leaveNow("room-1", "nobody");

        assertThat(log).isEmpty();
    }

    @Test
    void onStartRunsAfterRoomStateBroadcastAndBeforeGameStart() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);

        hub.subscribe("room-1").take(2).subscribe(message -> log.add(message.type()));

        dispatcher.start("room-1", HOST.participantId());

        assertThat(log).containsExactly("ROOM_STATE", "onStart", "GAME_START");
    }

    /** F2 regression: a throwing sink must degrade to an ERROR broadcast, not escape the dispatcher. */
    @Test
    void aThrowingSinkOnParticipantGoneProducesAnErrorInsteadOfPropagating() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        sink.throwOnParticipantGone = true;
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.leaveNow("room-1", GUEST.participantId()))
                .assertNext(message -> assertThat(message.type()).isEqualTo("ERROR"))
                .verifyComplete();
    }

    private static final class RecordingSink implements GameCommandSink {
        private final GameType gameType;
        private final List<String> log;
        boolean throwOnParticipantGone = false;

        RecordingSink(GameType gameType, List<String> log) {
            this.gameType = gameType;
            this.log = log;
        }

        @Override
        public GameType gameType() {
            return gameType;
        }

        @Override
        public void onStart(Room room) {
            log.add("onStart");
        }

        @Override
        public void onGameCommand(Room room, String participantId, ClientMessage message) {
            log.add("onGameCommand:" + participantId);
        }

        @Override
        public void onParticipantGone(Room room, String participantId) {
            log.add("onParticipantGone:" + participantId);
            if (throwOnParticipantGone) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
