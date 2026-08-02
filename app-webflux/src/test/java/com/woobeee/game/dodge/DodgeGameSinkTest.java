package com.woobeee.game.dodge;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.ws.ClientMessage;
import com.woobeee.game.ws.RoomHub;
import com.woobeee.game.ws.ServerMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DodgeGameSinkTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private RoomHub hub;
    private GameResultService resultService;
    private VirtualTimeScheduler scheduler;
    private DodgeGameSink sink;
    private Room room;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        hub = new RoomHub();
        resultService = mock(GameResultService.class);
        when(resultService.record(any(FinishedGame.class), any())).thenReturn(Mono.just(88L));
        scheduler = VirtualTimeScheduler.create();
        objectMapper = new ObjectMapper();

        sink = new DodgeGameSink(
                hub,
                resultService,
                new DodgeReplayWriter(objectMapper),
                idsReturning(12345),
                Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler
        );

        room = new Room("room-1", "code", GameType.DODGE, NOW, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));
        room.addMember(GameParticipant.guest("b", "손님2"));
    }

    private GameIdGenerator idsReturning(int seed) {
        return new GameIdGenerator() {
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
                return seed;
            }
        };
    }

    private ClientMessage moveMessage(String direction, long seq) {
        return new ClientMessage(
                "DODGE_MOVE",
                seq,
                objectMapper.readTree("{\"direction\":\"" + direction + "\"}")
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPayload(ServerMessage message) {
        return (Map<String, Object>) message.payload();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> positionsOf(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("positions");
    }

    /** Parses one recorded ndjson replay and returns the (non-header) line for a given tick, or null. */
    private JsonNode ndjsonLineForTick(String ndjson, int tick) {
        String[] lines = ndjson.strip().split("\n");
        for (int i = 1; i < lines.length; i++) {
            JsonNode line = objectMapper.readTree(lines[i]);
            if (line.get("tick").asInt() == tick) {
                return line;
            }
        }
        return null;
    }

    @Test
    void handlesTheDodgeGameType() {
        assertThat(sink.gameType()).isEqualTo(GameType.DODGE);
    }

    @Test
    void startSeedsTheGameFromTheIdGenerator() {
        sink.onStart(room);

        assertThat(sink.gameOf("room-1").seed()).isEqualTo(12345);
        assertThat(sink.gameOf("room-1").survivors()).hasSize(3);
    }

    /**
     * F3 — the timer must actually drive advanceOneTick *and* broadcast the result: deleting
     * either roomHub.broadcast call, or replacing the drained input with Map.of(), must fail
     * this test. Subscribing to the hub and asserting on the DODGE_TICK payload's shape (not
     * just DodgeGame.tick()) is what pins the client-visible contract.
     */
    @Test
    void theTimerDrivesTicksAndBroadcastsThem() {
        sink.onStart(room);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> scheduler.advanceTimeBy(Duration.ofMillis(100)))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("DODGE_TICK");
                    Map<String, Object> payload = asPayload(message);
                    assertThat(payload.get("tick")).isEqualTo(1);
                    assertThat(payload).containsKeys("positions", "obstacles", "eliminated");
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(sink.gameOf("room-1").tick()).isEqualTo(1);
    }

    /** GAME-AC-16 — 한 틱에 여러 번 눌러도 마지막 것만 반영된다. */
    @Test
    void onlyTheLastInputOfATickIsKept() {
        sink.onStart(room);

        sink.onGameCommand(room, "m:11", moveMessage("LEFT", 1L));
        sink.onGameCommand(room, "m:11", moveMessage("RIGHT", 2L));

        assertThat(sink.pendingInputOf("room-1", "m:11")).isEqualTo(Direction.RIGHT);
    }

    @Test
    void anUnknownDirectionIsIgnored() {
        sink.onStart(room);

        sink.onGameCommand(room, "m:11", moveMessage("sideways", 1L));

        assertThat(sink.pendingInputOf("room-1", "m:11")).isNull();
    }

    @Test
    void inputIsClearedAfterTheTickConsumesIt() {
        sink.onStart(room);
        sink.onGameCommand(room, "m:11", moveMessage("LEFT", 1L));

        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(sink.pendingInputOf("room-1", "m:11")).isNull();
    }

    /**
     * F5 — pins that a buffered move actually reaches DodgeGame.advanceOneTick (not just the
     * pendingInputs buffer) and is recorded in the ndjson handed to GameResultService.record.
     * Replacing the drained inputs with Map.of(), or deleting the recordedInputs.put(...) call,
     * must fail one of these two assertions.
     */
    @Test
    void bufferedInputMovesThePlayerAndIsRecordedInTheReplay() {
        sink.onStart(room);
        sink.onGameCommand(room, "m:11", moveMessage("LEFT", 1L));

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> scheduler.advanceTimeBy(Duration.ofMillis(100)))
                .assertNext(message -> {
                    Map<String, Object> payload = asPayload(message);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> positions = (List<Map<String, Object>>) payload.get("positions");
                    Map<String, Object> host = positions.stream()
                            .filter(p -> "m:11".equals(p.get("participantId")))
                            .findFirst().orElseThrow();
                    // Host is the first entry of DodgeRules.startingCells(3): x=2, bottom row.
                    // LEFT decrements x by one -- this is what proves the move actually reached
                    // the game, not just the pendingInputs buffer.
                    assertThat(host.get("x")).isEqualTo(1);
                    assertThat(host.get("y")).isEqualTo(DodgeRules.ROWS - 1);
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        ArgumentCaptor<String> ndjsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resultService).record(any(FinishedGame.class), ndjsonCaptor.capture());

        JsonNode tick0 = ndjsonLineForTick(ndjsonCaptor.getValue(), 0);
        assertThat(tick0).isNotNull();
        assertThat(tick0.get("moves").get("m:11").asString()).isEqualTo("LEFT");
    }

    /**
     * GAME-AC-23: a reconnecting player gets the current frame — the same {@code tick} /
     * {@code positions} / {@code obstacles} content a normal DODGE_TICK carries, so the client's
     * existing frame renderer handles it unchanged.
     *
     * <p>The assertions are deliberately against the <em>moved</em> position and the <em>live</em>
     * obstacle list captured from the preceding DODGE_TICK, not merely against the participant id
     * set: a snapshot built from the starting frame would have the right ids, an empty obstacle
     * list and the host still at its spawn column, and must not pass.
     *
     * <p>The tail assertion is the "does not advance the tick" half of GAME-AC-23. It lives here
     * rather than in its own test because on its own it passes against an {@code onRejoin} that
     * does nothing at all; paired with the broadcast assertion it means the snapshot really went
     * out <em>and</em> did not cost a tick.
     */
    @Test
    void aRejoinBroadcastsTheCurrentTickPositionsAndObstaclesWithoutAdvancing() {
        sink.onStart(room);
        // Move the host off its spawn column so a starting-frame snapshot is distinguishable.
        sink.onGameCommand(room, "m:11", moveMessage("LEFT", 1L));

        AtomicReference<Map<String, Object>> lastTick = new AtomicReference<>();
        hub.subscribe("room-1")
                .filter(message -> "DODGE_TICK".equals(message.type()))
                .subscribe(message -> lastTick.set(asPayload(message)));
        scheduler.advanceTimeBy(Duration.ofMillis(300));

        int tickNow = sink.gameOf("room-1").tick();
        Object obstaclesOnTheWire = lastTick.get().get("obstacles");
        assertThat((List<?>) obstaclesOnTheWire)
                .as("the scenario is only meaningful if obstacles actually exist by now")
                .isNotEmpty();

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onRejoin(room, "g:a"))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("GAME_SNAPSHOT");
                    Map<String, Object> payload = asPayload(message);
                    assertThat(payload).containsEntry("gameType", "DODGE");
                    assertThat(payload).containsEntry("tick", tickNow);
                    // Same obstacle cells the room was last told about, in the same order.
                    assertThat(payload).containsEntry("obstacles", obstaclesOnTheWire);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> positions =
                            (List<Map<String, Object>>) payload.get("positions");
                    assertThat(positions)
                            .containsExactlyInAnyOrderElementsOf(positionsOf(lastTick.get()));
                    // Host spawns at x=2 (first of DodgeRules.startingCells(3)); the LEFT above
                    // moved it to x=1 on tick 1 and nothing has moved it since.
                    assertThat(positions).contains(
                            Map.of("participantId", "m:11", "x", 1, "y", DodgeRules.ROWS - 1));
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertThat(sink.gameOf("room-1").tick()).isEqualTo(tickNow);
    }

    /**
     * Important-1 regression: the payload used to be computed under the game monitor and then
     * broadcast <em>after</em> releasing it. In that window the tick loop could take the monitor,
     * advance to N+1 and broadcast {@code DODGE_TICK(N+1)} — and only then would the stale
     * {@code GAME_SNAPSHOT(tick=N)} go out. Because GAME_SNAPSHOT is full state broadcast to the
     * whole room, every client would rewind a frame and then apply N+2 on top of it.
     * {@link RoomHub} serialising its emits does not help: the payload is computed before the
     * emit lock is taken.
     *
     * <p>This hub drives a real tick at the exact instant the snapshot is handed to the hub. If
     * the broadcast happens under the monitor, the ticking thread cannot proceed until the
     * snapshot is on the wire, so GAME_SNAPSHOT must be observed first and must still carry the
     * pre-tick counter.
     */
    @Test
    void aConcurrentTickCannotOvertakeTheSnapshotOnTheWire() throws InterruptedException {
        AtomicReference<DodgeGameSink> racingSink = new AtomicReference<>();
        AtomicReference<Thread> ticker = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean(false);

        RoomHub racingHub = new RoomHub() {
            @Override
            public Sinks.EmitResult broadcast(String roomId, ServerMessage message) {
                if ("GAME_SNAPSHOT".equals(message.type()) && fired.compareAndSet(false, true)) {
                    Thread thread = new Thread(() -> racingSink.get().onTick(room));
                    ticker.set(thread);
                    thread.start();
                    try {
                        // Give the tick every chance to win. Holding the monitor blocks it here;
                        // not holding it lets it advance and broadcast before we emit.
                        thread.join(500);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.broadcast(roomId, message);
            }
        };

        DodgeGameSink monitoredSink = new DodgeGameSink(
                racingHub,
                resultService,
                new DodgeReplayWriter(objectMapper),
                idsReturning(12345),
                Clock.fixed(NOW, ZoneOffset.UTC),
                VirtualTimeScheduler.create());
        racingSink.set(monitoredSink);
        monitoredSink.onStart(room);

        StepVerifier.create(racingHub.subscribe("room-1").take(2))
                .then(() -> monitoredSink.onRejoin(room, "g:a"))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("GAME_SNAPSHOT");
                    assertThat(asPayload(message)).containsEntry("tick", 0);
                })
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("DODGE_TICK");
                    assertThat(asPayload(message)).containsEntry("tick", 1);
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        ticker.get().join(VERIFY_TIMEOUT.toMillis());
    }

    /**
     * Important-2: {@code onParticipantGone} can finish the game (last survivor standing) while
     * leaving it in the {@code games} map — only the next tick evicts it. A player reconnecting
     * inside that window must not be handed a snapshot of a game that is already over; the
     * GAME_END that follows is the truth, and a snapshot would leave the board animating a game
     * with no survivors left to move.
     *
     * <p>Deleting the {@code finished()} guard from the sink makes this fail: a GAME_SNAPSHOT
     * arrives ahead of the PROBE.
     */
    @Test
    void aRejoinIntoAFinishedButNotYetEvictedGameBroadcastsNothing() {
        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");

        assertThat(sink.gameOf("room-1"))
                .as("the tick that evicts the finished game has not run yet")
                .isNotNull();
        assertThat(sink.gameOf("room-1").finished()).isTrue();

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onRejoin(room, "g:a"))
                .then(() -> hub.broadcast("room-1", ServerMessage.of("PROBE", Map.of())))
                .assertNext(message -> assertThat(message.type()).isEqualTo("PROBE"))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
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
    void aDepartedParticipantIsEliminated() {
        sink.onStart(room);

        sink.onParticipantGone(room, "g:a");

        assertThat(sink.gameOf("room-1").survivors()).doesNotContain("g:a");
    }

    @Test
    void theGameEndsAndRecordsAResult() {
        sink.onStart(room);

        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        ArgumentCaptor<FinishedGame> resultCaptor = ArgumentCaptor.forClass(FinishedGame.class);
        ArgumentCaptor<String> ndjsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resultService).record(resultCaptor.capture(), ndjsonCaptor.capture());

        FinishedGame finished = resultCaptor.getValue();
        assertThat(finished.gameType()).isEqualTo("DODGE");
        assertThat(finished.roomId()).isEqualTo("room-1");
        assertThat(finished.winnerParticipantId()).isEqualTo("m:11");
        assertThat(finished.participants()).hasSize(3);

        // Pin the ndjson actually recorded, not just that *some* string was passed.
        JsonNode header = objectMapper.readTree(ndjsonCaptor.getValue().strip().split("\n")[0]);
        assertThat(header.get("gameType").asString()).isEqualTo("DODGE");
        assertThat(header.get("seed").asInt()).isEqualTo(12345);
    }

    /**
     * F3 — GAME_END must carry no gameResultId (it is broadcast before GameResultService.record
     * completes, see the sink's javadoc) and must be the second message after the tick that
     * finished the game, with winnerParticipantId/ranks present.
     */
    @Test
    void gameEndBroadcastsRanksImmediatelyWithoutAGameResultId() {
        sink.onStart(room);

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> {
                    sink.onParticipantGone(room, "g:a");
                    sink.onParticipantGone(room, "g:b");
                    scheduler.advanceTimeBy(Duration.ofMillis(100));
                })
                .assertNext(message -> assertThat(message.type()).isEqualTo("DODGE_TICK"))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("GAME_END");
                    Map<String, Object> payload = asPayload(message);
                    assertThat(payload.get("winnerParticipantId")).isEqualTo("m:11");
                    assertThat(payload).containsKey("ranks");
                    assertThat(payload).doesNotContainKey("gameResultId");
                })
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    /**
     * F6 — every other departure test uses guests (memberId always null) and m:11 always
     * survives, so reverting the onStart memberId snapshot to a room.member(...) lookup at
     * finish time passed the whole suite: exactly the omok bug this sink was told to avoid.
     * Here a *member* departs (m:11), after RoomCommandDispatcher-style removal from the room
     * (room.removeMember before onParticipantGone, matching the real dispatcher's ordering), and
     * both the FinishedGame row and the recorded ndjson must still carry them.
     */
    @Test
    void aDepartedMemberIsStillRecordedWithTheirMemberId() {
        sink.onStart(room);
        room.removeMember("m:11");

        sink.onParticipantGone(room, "m:11");
        sink.onParticipantGone(room, "g:a");
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        ArgumentCaptor<FinishedGame> resultCaptor = ArgumentCaptor.forClass(FinishedGame.class);
        ArgumentCaptor<String> ndjsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resultService).record(resultCaptor.capture(), ndjsonCaptor.capture());

        FinishedGame finished = resultCaptor.getValue();
        assertThat(finished.winnerParticipantId()).isEqualTo("g:b");
        assertThat(finished.participants().stream()
                .filter(p -> p.participantId().equals("m:11"))
                .findFirst().orElseThrow().memberId()).isEqualTo(11L);

        JsonNode tick0 = ndjsonLineForTick(ndjsonCaptor.getValue(), 0);
        assertThat(tick0).isNotNull();
        assertThat(tick0.get("departures").get(0).asString()).isEqualTo("m:11");
        assertThat(tick0.get("departures").get(1).asString()).isEqualTo("g:a");
    }

    /**
     * Fix round 2, cosmetic — a game already finished by departure makes advanceOneTick a
     * no-op (it returns immediately without incrementing the tick counter), but the tick that
     * observes this still drains whatever was buffered in the meantime. Recording that drained
     * input regardless would leave a "moves" line in the ndjson tied to a tick that never
     * actually advanced -- ghost data no reader asked for, even though both DodgeReplayRunner
     * and a departures-aware client reader would break out before ever reading it today.
     */
    @Test
    void aTickThatDidNotAdvanceRecordsNoGhostMovesLine() {
        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");
        // Buffered after the game is already finished by departure, before the timer's next
        // tick drains it -- this must not leak into the recorded replay as a "moves" line.
        sink.onGameCommand(room, "m:11", moveMessage("LEFT", 1L));
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        ArgumentCaptor<String> ndjsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(resultService).record(any(FinishedGame.class), ndjsonCaptor.capture());

        JsonNode tick0 = ndjsonLineForTick(ndjsonCaptor.getValue(), 0);
        assertThat(tick0).isNotNull(); // the departures still belong on this line
        assertThat(tick0.has("moves")).isFalse();
    }

    /**
     * I1 — the tick loop is one {@code Flux.interval} subscription for the whole life of the
     * room. Anything thrown out of the tick body reaches that subscription as {@code onError},
     * which terminates the sequence for good: nothing resubscribes, so the room's game simply
     * stops advancing, in silence. {@link com.woobeee.game.room.RoomSweeper} already guards its
     * own interval exactly this way; this sink must too.
     *
     * <p>A throwing {@code roomHub.broadcast} stands in for "anything thrown from the tick body"
     * on a tick that does <b>not</b> end the game — that is the case where loop survival is
     * observable, because a finishing tick disposes the timer anyway. The next tick must still
     * run.
     */
    @Test
    void aThrowingTickDoesNotStopTheFollowingTicks() {
        RoomHub throwingHub = mock(RoomHub.class);
        when(throwingHub.broadcast(any(), any()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(Sinks.EmitResult.OK);

        DodgeGameSink guarded = new DodgeGameSink(
                throwingHub,
                resultService,
                new DodgeReplayWriter(objectMapper),
                idsReturning(12345),
                Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler
        );
        guarded.onStart(room);
        Disposable timer = guarded.timerOf("room-1");

        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(timer.isDisposed())
                .as("a throwing tick must not terminate the interval subscription")
                .isFalse();

        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(guarded.gameOf("room-1").tick())
                .as("the tick after a throwing one must still advance the game")
                .isEqualTo(2);
    }

    /**
     * I1 — the concrete hazard the review named: {@link GameResultService#record} can throw
     * synchronously (and {@link DodgeReplayWriter} wraps Jackson failures in an
     * {@link IllegalStateException}), and that throw happens inside {@code finish()}, past the
     * point where the per-room maps are cleaned up. Unguarded it escapes the tick, reaches the
     * interval as {@code onError}, and — because the subscribe had no error consumer — reactor
     * rethrows it out of whatever thread drove the tick, while every per-room map entry for the
     * finished room stays behind forever.
     *
     * <p>So: the throw must be contained and logged, and the room must leave no state behind.
     */
    @Test
    void aThrowingResultRecordingIsContainedAndStillReleasesTheRoomState() {
        when(resultService.record(any(FinishedGame.class), any()))
                .thenThrow(new IllegalStateException("storage exploded"));

        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");

        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(sink.holdsAnyStateFor("room-1"))
                .as("a throwing record must not leak the finished room's per-room maps")
                .isFalse();
    }

    /**
     * F4 — timer.dispose() lives in finish(), but the games map eviction that gameOf(...) reads
     * happens earlier, inside tick()'s atomic remove. Deleting timer.dispose() would leave
     * gameOf(...) null (so the old assertion alone would still pass) while the interval
     * subscription silently kept running. Capturing the Disposable before the game ends and
     * asserting isDisposed() afterward is what actually pins the timer stopping.
     */
    @Test
    void theTimerStopsWhenTheGameEnds() {
        sink.onStart(room);
        Disposable timer = sink.timerOf("room-1");

        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(sink.gameOf("room-1")).isNull();
        assertThat(timer.isDisposed()).isTrue();
    }
}
