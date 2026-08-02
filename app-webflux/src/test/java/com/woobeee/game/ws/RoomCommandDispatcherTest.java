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

    /**
     * 명령을 낸 세션 하나에게만 가는 프레임. 실제 서버에서는
     * {@code GameWebSocketHandler} 가 세션마다 두는 {@code Sinks.Many} 다.
     *
     * <p>이 목록과 {@link #hub} 를 나란히 보는 것이 I2 테스트의 요점이다: 어떤 실패가 어디로
     * 가는지를 <b>양쪽에서</b> 확인하지 않으면, 여전히 방 전체로 나가고 있어도 통과한다.
     */
    private final List<ServerMessage> personal = new CopyOnWriteArrayList<>();
    private final SessionChannel caller = personal::add;

    @BeforeEach
    void setUp() {
        personal.clear();
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
                .then(() -> dispatcher.ready("room-1", GUEST.participantId(), true, caller))
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
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> dispatcher.start("room-1", HOST.participantId(), caller))
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
            // 위의 isEmpty() 는 구독이 아예 붙지 않아도 통과한다. 성공한 ready 의
            // ROOM_STATE 를 쓴다 — 실패한 명령의 ERROR 는 이제 방으로 가지 않는다(I2).
            dispatcher.ready("room-1", HOST.participantId(), true, caller);
            assertThat(broadcasts)
                    .as("the collector really is wired to this room")
                    .hasSize(1);
            assertThat(broadcasts.getFirst().type()).isEqualTo("ROOM_STATE");
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
    void aFailedCommandEmitsErrorToTheCallerInsteadOfThrowing() {
        dispatcher.start("room-1", HOST.participantId(), caller);

        assertThat(personal).extracting(ServerMessage::type).containsExactly("ERROR");
    }

    /**
     * GAME-AC-28 — 소켓 ERROR 도 HTTP 봉투와 같은 코드를 싣는다. 코드가 없으면 화면은
     * 서버가 보낸 영어 문장을 그대로 띄우거나 뭉뚱그린 안내밖에 할 수 없다.
     */
    @Test
    void aFailedCommandCarriesTheErrorCodeNotJustAMessage() {
        dispatcher.start("room-1", HOST.participantId(), caller);

        assertThat(personal).hasSize(1);
        ErrorPayload payload = (ErrorPayload) personal.getFirst().payload();
        assertThat(payload.code()).isEqualTo(GameErrorCode.NOT_ENOUGH_PLAYERS.code());
        assertThat(payload.status()).isEqualTo(409);
    }

    /**
     * I2 — 방 안의 실패는 <b>그것을 낸 세션</b>에게만 간다.
     *
     * <p>예전에는 {@code guard} 가 무조건 허브로 흘려보냈다. 그래서 방장이 아닌 사람이 START
     * 를 한 번 누르면 "방장만 게임을 시작할 수 있습니다" 가 여덟 명 화면에 전부 떴다 — 정작
     * 방장에게도. {@code ackSeq} 로는 갈라낼 수 없다: seq 는 클라이언트마다 1부터 세므로 서로
     * 겹친다.
     *
     * <p>여기서 방 구독자를 실제로 붙여 두고 <b>아무것도 오지 않는 것</b>까지 확인한다.
     * {@code personal} 만 보면, 양쪽으로 다 보내는 구현도 통과한다.
     */
    @Test
    void aStartRefusedBecauseTheCallerIsNotTheHostReachesOnlyThatCaller() {
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);
        personal.clear();

        List<ServerMessage> broadcasts = new CopyOnWriteArrayList<>();
        Disposable subscription = hub.subscribe("room-1").subscribe(broadcasts::add);

        try {
            dispatcher.start("room-1", GUEST.participantId(), caller);

            assertThat(personal).hasSize(1);
            assertThat(((ErrorPayload) personal.getFirst().payload()).code())
                    .isEqualTo(GameErrorCode.NOT_HOST.code());
            assertThat(broadcasts)
                    .as("the room must not be told why one player's START failed")
                    .isEmpty();
        } finally {
            subscription.dispose();
        }
    }

    /** I2 — 준비 토글의 실패도 같다. 방이 알 이유가 없다. */
    @Test
    void aReadyRefusedForANonMemberReachesOnlyThatCaller() {
        List<ServerMessage> broadcasts = new CopyOnWriteArrayList<>();
        Disposable subscription = hub.subscribe("room-1").subscribe(broadcasts::add);

        try {
            dispatcher.ready("room-1", "not-a-member", true, caller);

            assertThat(personal).hasSize(1);
            assertThat(((ErrorPayload) personal.getFirst().payload()).code())
                    .isEqualTo(GameErrorCode.NOT_A_MEMBER.code());
            assertThat(broadcasts).isEmpty();
        } finally {
            subscription.dispose();
        }
    }

    /**
     * I2 의 경계 — {@link RoomCommandDispatcher#join} 이 입장 확정 전후를 가르는 것과 같은
     * 구분이다. {@code roomService.start} 가 통과한 뒤에 실패하면 방은 이미 IN_PROGRESS 로
     * 넘어갔고 그 ROOM_STATE 가 방에 나갔다. 그런데 게임은 시작되지 않았다 — 전원이
     * "시작됐다고 들었는데 아무것도 오지 않는" 상태다. 그건 진짜 방의 소식이므로 허브로 간다.
     */
    @Test
    void aStartThatFailsAfterTheRoomFlippedIsRoomNewsNotACallerError() {
        List<String> log = new ArrayList<>();
        RecordingSink sink = new RecordingSink(GameType.OMOK, log);
        sink.throwOnStart = true;
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of(sink));
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);
        personal.clear();

        List<ServerMessage> broadcasts = new CopyOnWriteArrayList<>();
        Disposable subscription = hub.subscribe("room-1").subscribe(broadcasts::add);

        try {
            dispatcher.start("room-1", HOST.participantId(), caller);

            assertThat(personal)
                    .as("the room already flipped, so this is not 'your START was refused'")
                    .isEmpty();
            assertThat(broadcasts).extracting(ServerMessage::type).contains("ERROR");
        } finally {
            subscription.dispose();
        }
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

        dispatcher.gameCommand("room-1", "not-a-member",
                new ClientMessage("OMOK_PLACE", 7L, null), caller);

        assertThat(personal).hasSize(1);
        ErrorPayload payload = (ErrorPayload) personal.getFirst().payload();
        assertThat(payload.code()).isEqualTo(GameErrorCode.NOT_A_MEMBER.code());
        assertThat(payload.status()).isEqualTo(403);
        // ackSeq 는 그대로 실려야 한다 — 화면이 "내가 낸 명령의 응답" 을 알아보는 근거다.
        assertThat(personal.getFirst().ackSeq()).isEqualTo(7L);
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
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);

        hub.subscribe("room-1").take(2).subscribe(message -> log.add(message.type()));

        dispatcher.start("room-1", HOST.participantId(), caller);

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
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);
        dispatcher.start("room-1", HOST.participantId(), caller);
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
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);
        dispatcher.start("room-1", HOST.participantId(), caller);
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

        dispatcher.gameCommand("room-1", "not-a-member",
                new ClientMessage("OMOK_PLACE", 7L, null), caller);

        assertThat(personal).extracting(ServerMessage::type).containsExactly("ERROR");
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
        dispatcher.ready("room-1", HOST.participantId(), true, caller);
        dispatcher.ready("room-1", GUEST.participantId(), true, caller);
        dispatcher.start("room-1", HOST.participantId(), caller);
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
        boolean throwOnStart = false;

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
            if (throwOnStart) {
                throw new IllegalStateException("boom");
            }
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
