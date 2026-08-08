package com.woobeee.game.omok;

import tools.jackson.databind.ObjectMapper;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomStatus;
import com.woobeee.game.ws.ClientMessage;
import com.woobeee.game.ws.RoomHub;
import com.woobeee.game.ws.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OmokGameSinkTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private RoomHub hub;
    private GameResultService resultService;
    private OmokGameSink sink;
    private Room room;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        hub = new RoomHub();
        resultService = mock(GameResultService.class);
        when(resultService.record(any(FinishedGame.class), anyString())).thenReturn(Mono.just(77L));
        objectMapper = new ObjectMapper();

        sink = new OmokGameSink(
                hub,
                resultService,
                new OmokReplayWriter(objectMapper),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        room = new Room("room-1", "code", GameType.OMOK, NOW, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));
    }

    private ClientMessage placeMessage(int x, int y, long seq) {
        return new ClientMessage(
                "OMOK_PLACE",
                seq,
                objectMapper.readTree("{\"x\":" + x + ",\"y\":" + y + "}")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPayload(ServerMessage message) {
        return (Map<String, Object>) message.payload();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> movesOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("moves");
    }

    @Test
    void handlesTheOmokGameType() {
        assertThat(sink.gameType()).isEqualTo(GameType.OMOK);
    }

    @Test
    void startAssignsBlackToTheHost() {
        sink.onStart(room);

        assertThat(sink.gameOf("room-1").blackParticipantId()).isEqualTo("m:11");
        assertThat(sink.gameOf("room-1").whiteParticipantId()).isEqualTo("g:a");
    }

    @Test
    void aValidPlacementBroadcastsOmokMoved() {
        sink.onStart(room);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onGameCommand(room, "m:11", placeMessage(7, 7, 1L)))
                .assertNext(message -> assertThat(message.type()).isEqualTo("OMOK_MOVED"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    /** GAME-AC-14 */
    @Test
    void anOutOfTurnPlacementBroadcastsOmokRejectedWithTheAckSeq() {
        sink.onStart(room);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onGameCommand(room, "g:a", placeMessage(7, 7, 9L)))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("OMOK_REJECTED");
                    assertThat(message.ackSeq()).isEqualTo(9L);
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void aWinBroadcastsGameEndAndRecordsTheResult() {
        sink.onStart(room);
        for (int i = 0; i < 4; i++) {
            sink.onGameCommand(room, "m:11", placeMessage(3 + i, 7, i * 2 + 1L));
            sink.onGameCommand(room, "g:a", placeMessage(3 + i, 9, i * 2 + 2L));
        }

        // A winning placement is still a successful placement: OMOK_MOVED carries the
        // coordinates of the last stone (GAME_END never does), then GAME_END follows.
        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> sink.onGameCommand(room, "m:11", placeMessage(7, 7, 99L)))
                .assertNext(message -> assertThat(message.type()).isEqualTo("OMOK_MOVED"))
                .assertNext(message -> assertThat(message.type()).isEqualTo("GAME_END"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        ArgumentCaptor<FinishedGame> captor = ArgumentCaptor.forClass(FinishedGame.class);
        verify(resultService).record(captor.capture(), anyString());

        FinishedGame recorded = captor.getValue();
        assertThat(recorded.gameType()).isEqualTo("OMOK");
        assertThat(recorded.roomId()).isEqualTo("room-1");
        assertThat(recorded.winnerParticipantId()).isEqualTo("m:11");
        assertThat(recorded.participants()).hasSize(2);
        assertThat(recorded.participants().stream()
                .filter(p -> p.participantId().equals("m:11"))
                .findFirst().orElseThrow().finishRank()).isEqualTo(1);
        assertThat(recorded.participants().stream()
                .filter(p -> p.participantId().equals("g:a"))
                .findFirst().orElseThrow().memberId()).isNull();
    }

    /**
     * GAME-AC-30 (formerly known-gap G3): the rematch flow is gated on the room being
     * {@code FINISHED}, so {@code OmokGameSink.finish} must flip {@code room.status()} when the
     * game ends. Without this the room stays {@code IN_PROGRESS} until its 6-hour TTL sweep and
     * a rematch is never possible.
     */
    @DisplayName("a win flips the room's status to FINISHED")
    @Test
    void aWinFlipsTheRoomStatusToFinished() {
        room.setStatus(RoomStatus.IN_PROGRESS);
        sink.onStart(room);
        for (int i = 0; i < 4; i++) {
            sink.onGameCommand(room, "m:11", placeMessage(3 + i, 7, i * 2 + 1L));
            sink.onGameCommand(room, "g:a", placeMessage(3 + i, 9, i * 2 + 2L));
        }

        sink.onGameCommand(room, "m:11", placeMessage(7, 7, 99L));

        assertThat(room.status()).isEqualTo(RoomStatus.FINISHED);
    }

    /**
     * GAME-AC-23: a reconnecting player must be able to redraw the board. The snapshot carries the
     * move list in order, each move self-describing its colour, so the client needs no separate
     * header to know which stone is which.
     *
     * <p>The tail assertions are the "does not disturb the game" half of GAME-AC-23, kept in this
     * test rather than a separate one on purpose: on their own they pass against an {@code
     * onRejoin} that does nothing at all, which pins nothing. Paired with the broadcast assertion
     * above they mean something — the snapshot really went out, <em>and</em> producing it consumed
     * neither the turn nor a move.
     */
    @Test
    void aRejoinBroadcastsEveryMoveSoFarWithTheCurrentTurnAndConsumesNeither() {
        sink.onStart(room);
        sink.onGameCommand(room, "m:11", placeMessage(7, 7, 1L));
        sink.onGameCommand(room, "g:a", placeMessage(8, 8, 2L));

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onRejoin(room, "g:a"))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("GAME_SNAPSHOT");
                    Map<String, Object> payload = asPayload(message);
                    assertThat(payload).containsEntry("gameType", "OMOK");
                    assertThat(payload).containsEntry("nextTurn", "m:11");
                    assertThat(payload).containsEntry(
                            "turnDeadline", NOW.plus(OmokGameSink.MOVE_LIMIT).toString());
                    assertThat(movesOf(payload)).containsExactly(
                            Map.of("x", 7, "y", 7, "color", "BLACK"),
                            Map.of("x", 8, "y", 8, "color", "WHITE"));
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(sink.gameOf("room-1").currentTurnParticipantId()).isEqualTo("m:11");
        assertThat(sink.gameOf("room-1").moves()).hasSize(2);
    }

    /**
     * Important-1 regression: the payload used to be computed under the game monitor and then
     * broadcast <em>after</em> releasing it. In that window a concurrent OMOK_PLACE could take the
     * monitor, land move N+1 and broadcast {@code OMOK_MOVED(N+1)} — and only then would the stale
     * {@code GAME_SNAPSHOT([1..N])} go out. Because GAME_SNAPSHOT is full state broadcast to the
     * whole room, every client would rewind, lose move N+1 for good, hold the wrong turn, and
     * apply move N+2 to a diverged board. {@link RoomHub} serialising its emits does not help:
     * the payload is computed before the emit lock is taken.
     *
     * <p>This hub fires a real, legal placement by the other player at the exact instant the
     * snapshot is handed to the hub. If the broadcast happens under the monitor, that thread
     * cannot proceed until the snapshot is on the wire, so GAME_SNAPSHOT must be observed first
     * and must still carry only the single move that existed when it was taken.
     */
    @Test
    void aConcurrentPlacementCannotOvertakeTheSnapshotOnTheWire() throws InterruptedException {
        AtomicReference<OmokGameSink> racingSink = new AtomicReference<>();
        AtomicReference<Thread> placer = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean(false);

        RoomHub racingHub = new RoomHub() {
            @Override
            public Sinks.EmitResult broadcast(String roomId, ServerMessage message) {
                if ("GAME_SNAPSHOT".equals(message.type()) && fired.compareAndSet(false, true)) {
                    Thread thread = new Thread(() ->
                            racingSink.get().onGameCommand(room, "g:a", placeMessage(8, 8, 2L)));
                    placer.set(thread);
                    thread.start();
                    try {
                        // Give the placement every chance to win. Holding the monitor blocks it
                        // here; not holding it lets it land and broadcast before we emit.
                        thread.join(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.broadcast(roomId, message);
            }
        };

        OmokGameSink monitoredSink = new OmokGameSink(
                racingHub, resultService, new OmokReplayWriter(objectMapper), Clock.fixed(NOW, ZoneOffset.UTC));
        racingSink.set(monitoredSink);
        monitoredSink.onStart(room);
        monitoredSink.onGameCommand(room, "m:11", placeMessage(7, 7, 1L));

        StepVerifier.create(racingHub.subscribe("room-1").take(2))
                .then(() -> monitoredSink.onRejoin(room, "g:a"))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("GAME_SNAPSHOT");
                    assertThat(movesOf(asPayload(message)))
                            .containsExactly(Map.of("x", 7, "y", 7, "color", "BLACK"));
                })
                .assertNext(message -> assertThat(message.type()).isEqualTo("OMOK_MOVED"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        placer.get().join(VERIFY_TIMEOUT.toMillis());
    }

    /**
     * Important-2: {@code place()} flips {@code finished} inside the monitor, but {@code finish()}
     * only evicts the game from the map afterwards — and the winning OMOK_MOVED is broadcast in
     * between. A player reconnecting inside that window must not be handed a snapshot of a game
     * that is already decided: the GAME_END arriving right behind it is the truth, and a snapshot
     * would leave the board showing a live game with a turn that will never come.
     *
     * <p>Deleting the {@code finished()} guard from the sink makes this fail: a GAME_SNAPSHOT
     * appears ahead of the OMOK_MOVED that opened the window.
     */
    @Test
    void aRejoinInsideTheWindowWhereTheGameIsFinishedButStillMappedBroadcastsNothing() {
        AtomicReference<OmokGameSink> sinkRef = new AtomicReference<>();
        AtomicBoolean atTheWinningMove = new AtomicBoolean(false);
        AtomicBoolean windowWasReal = new AtomicBoolean(false);

        RoomHub windowHub = new RoomHub() {
            @Override
            public Sinks.EmitResult broadcast(String roomId, ServerMessage message) {
                if (atTheWinningMove.compareAndSet(true, false) && "OMOK_MOVED".equals(message.type())) {
                    OmokGame stillMapped = sinkRef.get().gameOf("room-1");
                    windowWasReal.set(stillMapped != null && stillMapped.finished());
                    sinkRef.get().onRejoin(room, "g:a");
                }
                return super.broadcast(roomId, message);
            }
        };

        OmokGameSink windowSink = new OmokGameSink(
                windowHub, resultService, new OmokReplayWriter(objectMapper), Clock.fixed(NOW, ZoneOffset.UTC));
        sinkRef.set(windowSink);
        windowSink.onStart(room);
        for (int i = 0; i < 4; i++) {
            windowSink.onGameCommand(room, "m:11", placeMessage(3 + i, 7, i * 2 + 1L));
            windowSink.onGameCommand(room, "g:a", placeMessage(3 + i, 9, i * 2 + 2L));
        }

        StepVerifier.create(windowHub.subscribe("room-1").take(2))
                .then(() -> {
                    atTheWinningMove.set(true);
                    windowSink.onGameCommand(room, "m:11", placeMessage(7, 7, 99L));
                })
                .assertNext(message -> assertThat(message.type()).isEqualTo("OMOK_MOVED"))
                .assertNext(message -> assertThat(message.type()).isEqualTo("GAME_END"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(windowWasReal)
                .as("the rejoin must have happened while the game was finished but still mapped")
                .isTrue();
    }

    /**
     * GAME-AC-24: reconnecting into a room whose game never started has nothing to replay. The
     * PROBE arriving first is what proves no snapshot was queued ahead of it — a bounded way to
     * assert an absence.
     */
    @Test
    void aRejoinWithNoGameInProgressBroadcastsNothing() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onRejoin(room, "g:a"))
                .then(() -> hub.broadcast("room-1", ServerMessage.of("PROBE", Map.of())))
                .assertNext(message -> assertThat(message.type()).isEqualTo("PROBE"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    @Test
    void aDepartedParticipantResignsAndTheOpponentWins() {
        sink.onStart(room);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onParticipantGone(room, "g:a"))
                .assertNext(message -> assertThat(message.type()).isEqualTo("GAME_END"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        ArgumentCaptor<FinishedGame> captor = ArgumentCaptor.forClass(FinishedGame.class);
        verify(resultService).record(captor.capture(), anyString());
        assertThat(captor.getValue().winnerParticipantId()).isEqualTo("m:11");
    }

    /**
     * RoomCommandDispatcher.settle removes the member from the room before notifying the
     * sink (see its javadoc), so by the time onParticipantGone runs, room.member(...) can
     * no longer resolve the departed participant's memberId. A logged-in member who
     * resigns by leaving must still show up in match history with their memberId, not a
     * null one that would drop the game from their history and block their replay access.
     */
    @Test
    void aDepartedMemberIsStillRecordedWithTheirMemberId() {
        sink.onStart(room);
        room.removeMember("m:11");

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onParticipantGone(room, "m:11"))
                .assertNext(message -> assertThat(message.type()).isEqualTo("GAME_END"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        ArgumentCaptor<FinishedGame> captor = ArgumentCaptor.forClass(FinishedGame.class);
        verify(resultService).record(captor.capture(), anyString());

        assertThat(captor.getValue().participants().stream()
                .filter(p -> p.participantId().equals("m:11"))
                .findFirst().orElseThrow().memberId()).isEqualTo(11L);
    }

    @Test
    void aDepartureAfterTheGameEndedDoesNotRecordTwice() {
        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "m:11");

        verify(resultService).record(any(FinishedGame.class), anyString());
    }

    /**
     * G1 known gap: {@link OmokGame#timeout(Instant)} correctly decides a stalled player loses
     * (see {@code OmokGameTest.exceedingTheMoveLimitLosesTheGame}), but nothing in
     * {@link OmokGameSink} ever calls it — there is no timer, no scheduled sweep, no per-move
     * check. A player who simply never moves again stalls the game forever: no GAME_END, no
     * recorded result, no way for the opponent to be declared the winner.
     *
     * <p>This test uses a movable {@link Clock} (the sink's actual dependency, not a stand-in) so
     * the failure is attributable purely to missing wiring: the clock is advanced instantly, well
     * past {@link OmokGameSink#MOVE_LIMIT}, with no real sleep involved. If any wiring drove the
     * timeout — a scheduled sweep, a check triggered by the clock advancing — GAME_END would
     * follow on its own, since no client message is ever sent here. The bounded
     * {@code verify(Duration)} below is what turns "nothing ever happens" into an observable,
     * timely failure instead of an unbounded hang.
     */
    @Tag("known-gap")
    @DisplayName("G1: the 60s move-limit timeout is never enforced by the sink")
    @Test
    void aStalledPlayerNeverTimesOutBecauseNothingDrivesTheDeadline() {
        AtomicReference<Instant> movableNow = new AtomicReference<>(NOW);
        Clock movableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return movableNow.get();
            }
        };

        OmokGameSink timeoutSink = new OmokGameSink(
                hub, resultService, new OmokReplayWriter(objectMapper), movableClock);
        timeoutSink.onStart(room);

        // Jump straight past the deadline -- no sleeping, no waiting for real time to pass.
        movableNow.set(NOW.plus(OmokGameSink.MOVE_LIMIT).plusSeconds(1));

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .expectNextMatches(message -> message.type().equals("GAME_END"))
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }
}
