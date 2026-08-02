package com.woobeee.game.dodge;

import tools.jackson.databind.ObjectMapper;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.ws.ClientMessage;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

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

class DodgeGameSinkTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

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
        when(resultService.record(any(FinishedGame.class), anyString())).thenReturn(Mono.just(88L));
        scheduler = VirtualTimeScheduler.create();
        objectMapper = new ObjectMapper();

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
                return 12345;
            }
        };

        sink = new DodgeGameSink(
                hub,
                resultService,
                new DodgeReplayWriter(objectMapper),
                ids,
                Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler
        );

        room = new Room("room-1", "code", GameType.DODGE, NOW, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));
        room.addMember(GameParticipant.guest("b", "손님2"));
    }

    private ClientMessage moveMessage(String direction, long seq) {
        return new ClientMessage(
                "DODGE_MOVE",
                seq,
                objectMapper.readTree("{\"direction\":\"" + direction + "\"}")
        );
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

    @Test
    void theTimerDrivesTicksAndBroadcastsThem() {
        sink.onStart(room);

        int before = sink.gameOf("room-1").tick();
        scheduler.advanceTimeBy(Duration.ofMillis(300));

        assertThat(sink.gameOf("room-1").tick()).isGreaterThan(before);
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

        ArgumentCaptor<FinishedGame> captor = ArgumentCaptor.forClass(FinishedGame.class);
        verify(resultService).record(captor.capture(), anyString());

        FinishedGame finished = captor.getValue();
        assertThat(finished.gameType()).isEqualTo("DODGE");
        assertThat(finished.roomId()).isEqualTo("room-1");
        assertThat(finished.winnerParticipantId()).isEqualTo("m:11");
        assertThat(finished.participants()).hasSize(3);
    }

    @Test
    void theTimerStopsWhenTheGameEnds() {
        sink.onStart(room);
        sink.onParticipantGone(room, "g:a");
        sink.onParticipantGone(room, "g:b");
        scheduler.advanceTimeBy(Duration.ofMillis(100));

        assertThat(sink.gameOf("room-1")).isNull();
    }
}
