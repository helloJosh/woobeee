package com.woobeee.game.ws;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.ConnectionState;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.ws.payload.ErrorPayload;
import com.woobeee.game.ws.payload.RoomStatePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /**
     * GAME-AC-28 — 거절당한 참가 시도는 <b>방에 알리지 않는다</b>. 거절당한 사람은 아직 방
     * 사람이 아니므로 그 실패는 방의 일이 아니고, 무엇보다 그 세션은 아직 허브를 구독하지
     * 않아 브로드캐스트가 정작 당사자에게는 닿지 않는다 — 남들만 남의 실패를 구경하게 된다.
     * 대신 이유를 호출자에게 돌려주고, 호출자가 그 세션에 직접 써 준다.
     */
    @Test
    void aRejectedJoinTellsTheCallerWhyAndDoesNotBotherTheRoom() {
        // StepVerifier 의 expectNoEvent 로는 이걸 검사할 수 없다 — .then() 안에서 동기적으로
        // emit 된 신호는 no-event 창이 열리기 전에 이미 지나가서 그냥 통과한다(실제로 그렇게
        // 통과했다). 그래서 구독자를 직접 붙여 받은 것을 리스트로 확인한다.
        List<ServerMessage> broadcasts = new CopyOnWriteArrayList<>();
        Disposable subscription = hub.subscribe("room-1").subscribe(broadcasts::add);

        try {
            assertThat(dispatcher.join("room-1", "WRONG", GUEST))
                    .contains(GameErrorCode.INVALID_INVITE_CODE);
            assertThat(broadcasts)
                    .as("a rejected join must not be announced to the room")
                    .isEmpty();

            // 대조군: 같은 구독자가 실제 브로드캐스트는 받는다는 것을 보인다. 이게 없으면
            // 위의 isEmpty() 는 구독이 아예 붙지 않아도 통과한다.
            dispatcher.start("room-1", GUEST.participantId());
            assertThat(broadcasts)
                    .as("the collector really is wired to this room")
                    .hasSize(1);
            assertThat(broadcasts.getFirst().type()).isEqualTo("ERROR");
        } finally {
            subscription.dispose();
        }
    }

    /** GAME-AC-28 — 성공한 참가는 아무 이유도 돌려주지 않는다. */
    @Test
    void anAcceptedJoinReportsNoReason() {
        assertThat(dispatcher.join("room-1", "code", GUEST)).isEmpty();
    }

    @Test
    void aFailedCommandEmitsErrorToTheHubInsteadOfThrowing() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .assertNext(message -> assertThat(message.type()).isEqualTo("ERROR"))
                .verifyComplete();
    }

    /**
     * GAME-AC-28 — 소켓 ERROR 도 HTTP 봉투와 같은 코드를 싣는다. 코드가 없으면 화면은
     * 서버가 보낸 영어 문장을 그대로 띄우거나 뭉뚱그린 안내밖에 할 수 없다.
     */
    @Test
    void aFailedCommandCarriesTheErrorCodeNotJustAMessage() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("ERROR");
                    ErrorPayload payload = (ErrorPayload) message.payload();
                    assertThat(payload.code()).isEqualTo(GameErrorCode.NOT_ENOUGH_PLAYERS.code());
                    assertThat(payload.status()).isEqualTo(409);
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    /**
     * GAME-AC-28 — 카탈로그 밖에서 올라온 예외도 봉투 모양은 지킨다. 그리고 예외 메시지는
     * 싣지 않는다 — HTTP 쪽 catch-all 과 같은 규칙이다.
     */
    @Test
    void anUnexpectedFailureCarriesTheGenericCodeAndLeaksNothing() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        sink.throwOnParticipantGone = true;
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.leaveNow("room-1", GUEST.participantId()))
                .assertNext(message -> {
                    ErrorPayload payload = (ErrorPayload) message.payload();
                    assertThat(payload.code()).isEqualTo(GameErrorCode.UNEXPECTED.code());
                    assertThat(payload.status()).isEqualTo(500);
                    assertThat(payload.message()).doesNotContain("boom");
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    /**
     * GAME-AC-28 — 방 멤버가 아닌 세션의 게임 명령. 여기가 카탈로그를 우회해 bare
     * ResponseStatusException 을 던지던 21번째 호출부였다.
     */
    @Test
    void aGameCommandFromANonMemberCarriesTheNotAMemberCode() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.gameCommand("room-1", "not-a-member",
                        new ClientMessage("OMOK_PLACE", 7L, null)))
                .assertNext(message -> {
                    ErrorPayload payload = (ErrorPayload) message.payload();
                    assertThat(payload.code()).isEqualTo(GameErrorCode.NOT_A_MEMBER.code());
                    assertThat(payload.status()).isEqualTo(403);
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
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

    /**
     * GAME-AC-23: a player who dropped out of a running game and came back inside the grace
     * window must be handed the game state again — otherwise they keep their seat and stare at
     * an empty board while the game continues without them.
     */
    @Test
    void aReconnectIntoARunningGameAsksTheSinkForASnapshot() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);
        dispatcher.start("room-1", HOST.participantId());
        dispatcher.disconnected("room-1", GUEST.participantId());
        log.clear();

        dispatcher.join("room-1", "code", GUEST);

        assertThat(log).containsExactly("onRejoin:" + GUEST.participantId());
    }

    /** GAME-AC-24: a newcomer has nothing to catch up on — the ROOM_STATE is the whole story. */
    @Test
    void aFirstTimeJoinAsksForNoSnapshot() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));

        dispatcher.join("room-1", "code", GUEST);

        assertThat(log).isEmpty();
    }

    /** GAME-AC-24: reconnecting into a lobby has no game state to replay. */
    @Test
    void aReconnectIntoARoomWhoseGameHasNotStartedAsksForNoSnapshot() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.disconnected("room-1", GUEST.participantId());
        log.clear();

        dispatcher.join("room-1", "code", GUEST);

        assertThat(log).isEmpty();
    }

    /** GAME-AC-23: the snapshot follows the ROOM_STATE that announced the reconnect, never precedes it. */
    @Test
    void theSnapshotIsRequestedAfterTheRoomStateBroadcast() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);
        dispatcher.start("room-1", HOST.participantId());
        dispatcher.disconnected("room-1", GUEST.participantId());
        log.clear();

        hub.subscribe("room-1").take(1).subscribe(message -> log.add(message.type()));
        dispatcher.join("room-1", "code", GUEST);

        assertThat(log).containsExactly("ROOM_STATE", "onRejoin:" + GUEST.participantId());
    }

    /**
     * C2 regression: {@code requireRoomById} only checks the room exists, not that the caller is
     * a member of it. A non-member who merely knows the roomId (e.g. a session whose JOIN failed
     * invite-code validation, or one from a different room entirely) must not have its game
     * commands reach a registered sink.
     */
    @Test
    void gameCommandFromANonMemberDoesNotReachTheSink() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.gameCommand("room-1", "not-a-member",
                        new ClientMessage("OMOK_PLACE", 7L, null)))
                .assertNext(message -> assertThat(message.type()).isEqualTo("ERROR"))
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertThat(log).isEmpty();
    }

    /**
     * GAME-AC-28 경계 — 참가가 <b>확정된 뒤</b> 후속 작업이 실패한 것은 거절이 아니다.
     *
     * <p>이 구분이 중요한 이유는 호출자 쪽 불변식 때문이다: 거절을 돌려받은 세션에만
     * {@code GameWebSocketHandler.rejectWithReason} 이 세션에 직접 프레임을 쓴다. 그런데
     * 확정 이후라면 그 세션은 이미 허브를 구독했고 outbound 스트림이 살아 있으므로, 거기에
     * 대고 두 번째 {@code session.send} 를 걸면 같은 소켓에 두 writer 가 붙는다. 그래서
     * 확정 이후의 실패는 이유를 돌려주지 않고 — 이미 구독 중이니 닿는다 — 방으로 흘려보낸다.
     */
    @Test
    void aFailureAfterAdmissionIsNotAJoinRejectionAndGoesToTheRoomInstead() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);
        dispatcher.start("room-1", HOST.participantId());
        dispatcher.disconnected("room-1", GUEST.participantId());
        sink.throwOnRejoin = true;

        List<ServerMessage> broadcasts = new CopyOnWriteArrayList<>();
        Disposable subscription = hub.subscribe("room-1").subscribe(broadcasts::add);

        try {
            assertThat(dispatcher.join("room-1", "code", GUEST))
                    .as("admission succeeded, so this is not a rejection")
                    .isEmpty();
            assertThat(broadcasts).extracting(ServerMessage::type).contains("ERROR");
            ErrorPayload payload = (ErrorPayload) broadcasts.stream()
                    .filter(message -> message.type().equals("ERROR"))
                    .findFirst()
                    .orElseThrow()
                    .payload();
            assertThat(payload.code()).isEqualTo(GameErrorCode.UNEXPECTED.code());
        } finally {
            subscription.dispose();
        }
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
        boolean throwOnRejoin = false;

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
        public void onRejoin(Room room, String participantId) {
            log.add("onRejoin:" + participantId);
            if (throwOnRejoin) {
                throw new IllegalStateException("boom");
            }
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
