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
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
     * G3 known gap: nothing ever flips {@link RoomStatus} to {@code FINISHED} once a game ends.
     * {@code OmokGameSink.finish} broadcasts GAME_END, records the result, and clears its own
     * per-room maps, but never touches {@code room.status()} — so the room the client-visible
     * ROOM_STATE payload describes stays {@code IN_PROGRESS} until its 6-hour TTL sweep, and
     * nothing (e.g. a rematch flow gated on the room being finished/waiting) can rely on the
     * room's own status to know the game is over.
     */
    @Tag("known-gap")
    @DisplayName("G3: a win never flips the room's status out of IN_PROGRESS")
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
     * move list in order rather than a board grid, so the client replays it with the very same
     * code the replay viewer already uses.
     */
    @Test
    void aRejoinBroadcastsEveryMoveSoFarWithTheCurrentTurn() {
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
    }

    /** GAME-AC-23: reading state for the snapshot must not be a move. */
    @Test
    void aRejoinLeavesTheGameExactlyAsItWas() {
        sink.onStart(room);
        sink.onGameCommand(room, "m:11", placeMessage(7, 7, 1L));
        String turnBefore = sink.gameOf("room-1").currentTurnParticipantId();
        int movesBefore = sink.gameOf("room-1").moves().size();

        sink.onRejoin(room, "g:a");

        assertThat(sink.gameOf("room-1").currentTurnParticipantId()).isEqualTo(turnBefore);
        assertThat(sink.gameOf("room-1").moves()).hasSize(movesBefore);
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
