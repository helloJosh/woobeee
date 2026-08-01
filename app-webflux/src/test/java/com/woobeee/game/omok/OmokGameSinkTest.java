package com.woobeee.game.omok;

import tools.jackson.databind.ObjectMapper;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.ws.ClientMessage;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

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

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> sink.onGameCommand(room, "m:11", placeMessage(7, 7, 99L)))
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

    @Test
    void aDepartureAfterTheGameEndedDoesNotRecordTwice() {
        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "m:11");

        verify(resultService).record(any(FinishedGame.class), anyString());
    }
}
