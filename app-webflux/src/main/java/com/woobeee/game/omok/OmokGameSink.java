package com.woobeee.game.omok;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.FinishedParticipant;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomMember;
import com.woobeee.game.ws.ClientMessage;
import com.woobeee.game.ws.GameCommandSink;
import com.woobeee.game.ws.RoomHub;
import com.woobeee.game.ws.ServerMessage;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 순수한 OmokGame 을 방과 이어 붙이는 유일한 지점.
 *
 * <p>모든 메서드는 방 명령 큐 안에서 불리므로 같은 방에 대해 동시에 실행되지 않는다.
 */
@Component
public class OmokGameSink implements GameCommandSink {
    public static final Duration MOVE_LIMIT = Duration.ofSeconds(60);

    private final Map<String, OmokGame> games = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> displayNames = new ConcurrentHashMap<>();

    private final RoomHub roomHub;
    private final GameResultService gameResultService;
    private final OmokReplayWriter replayWriter;
    private final Clock clock;

    public OmokGameSink(
            RoomHub roomHub,
            GameResultService gameResultService,
            OmokReplayWriter replayWriter,
            Clock clock
    ) {
        this.roomHub = roomHub;
        this.gameResultService = gameResultService;
        this.replayWriter = replayWriter;
        this.clock = clock;
    }

    @Override
    public GameType gameType() {
        return GameType.OMOK;
    }

    /** 테스트에서 상태를 들여다보기 위한 접근자. */
    public OmokGame gameOf(String roomId) {
        return games.get(roomId);
    }

    @Override
    public void onStart(Room room) {
        List<RoomMember> members = room.members();
        String black = room.hostParticipantId();
        String white = members.stream()
                .map(member -> member.participant().participantId())
                .filter(id -> !id.equals(black))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Omok needs two players"));

        Instant now = clock.instant();
        games.put(room.roomId(), new OmokGame(black, white, now, MOVE_LIMIT));
        startedAt.put(room.roomId(), now);

        Map<String, String> names = new LinkedHashMap<>();
        members.forEach(member -> names.put(
                member.participant().participantId(),
                member.participant().displayName()
        ));
        displayNames.put(room.roomId(), names);
    }

    @Override
    public void onGameCommand(Room room, String participantId, ClientMessage message) {
        if (!"OMOK_PLACE".equals(message.type())) {
            return;
        }

        OmokGame game = games.get(room.roomId());
        if (game == null) {
            return;
        }

        int x = message.payload() == null ? -1 : message.payload().path("x").asInt(-1);
        int y = message.payload() == null ? -1 : message.payload().path("y").asInt(-1);

        PlaceOutcome outcome = game.place(participantId, x, y, clock.instant());

        switch (outcome.status()) {
            case REJECTED -> roomHub.broadcast(room.roomId(), ServerMessage.ack(
                    "OMOK_REJECTED",
                    message.seq(),
                    Map.of("reason", outcome.reason())
            ));
            case PLACED -> roomHub.broadcast(room.roomId(), ServerMessage.ack(
                    "OMOK_MOVED",
                    message.seq(),
                    Map.of(
                            "participantId", participantId,
                            "x", x,
                            "y", y,
                            "color", outcome.stone().name(),
                            "nextTurn", game.currentTurnParticipantId(),
                            "turnDeadline", outcome.turnDeadline().toString()
                    )
            ));
            // 승리 착수도 착수 성공이다 — 돌은 이미 놓였고 moves 에도 기록됐다. OMOK_MOVED 로
            // 마지막 돌의 좌표를 먼저 알리고, 그다음 finish() 가 GAME_END 를 보낸다. GAME_END
            // 페이로드에는 좌표가 없으므로 이 순서를 지키지 않으면 클라이언트 판에 승리한 돌이
            // 영영 그려지지 않는다.
            case WIN -> {
                roomHub.broadcast(room.roomId(), ServerMessage.ack(
                        "OMOK_MOVED",
                        message.seq(),
                        Map.of(
                                "participantId", participantId,
                                "x", x,
                                "y", y,
                                "color", outcome.stone().name()
                        )
                ));
                finish(room, game, outcome.winnerParticipantId());
            }
        }
    }

    @Override
    public void onParticipantGone(Room room, String participantId) {
        OmokGame game = games.get(room.roomId());
        if (game == null || game.finished()) {
            return;
        }

        PlaceOutcome outcome = game.resign(participantId);
        if (outcome.status() == PlaceOutcome.Status.WIN) {
            finish(room, game, outcome.winnerParticipantId());
        }
    }

    private void finish(Room room, OmokGame game, String winnerParticipantId) {
        Map<String, String> names = displayNames.getOrDefault(room.roomId(), Map.of());
        Instant start = startedAt.getOrDefault(room.roomId(), clock.instant());

        roomHub.broadcast(room.roomId(), ServerMessage.of("GAME_END", Map.of(
                "winnerParticipantId", winnerParticipantId == null ? "" : winnerParticipantId,
                "ranks", ranksOf(game, winnerParticipantId, names)
        )));

        FinishedGame finished = new FinishedGame(
                "OMOK",
                room.roomId(),
                start,
                clock.instant(),
                winnerParticipantId,
                participantsOf(room, game, winnerParticipantId, names)
        );

        String ndjson = replayWriter.toNdjson(game, names);
        gameResultService.record(finished, ndjson).subscribe();

        games.remove(room.roomId());
        startedAt.remove(room.roomId());
        displayNames.remove(room.roomId());
    }

    private List<Map<String, Object>> ranksOf(OmokGame game, String winner, Map<String, String> names) {
        String loser = game.opponentOf(winner);
        return List.of(
                Map.of("participantId", winner, "displayName", names.getOrDefault(winner, winner), "rank", 1),
                Map.of("participantId", loser, "displayName", names.getOrDefault(loser, loser), "rank", 2)
        );
    }

    private List<FinishedParticipant> participantsOf(
            Room room,
            OmokGame game,
            String winner,
            Map<String, String> names
    ) {
        return List.of(game.blackParticipantId(), game.whiteParticipantId()).stream()
                .map(id -> new FinishedParticipant(
                        id,
                        names.getOrDefault(id, id),
                        memberIdOf(room, id),
                        id.equals(winner) ? 1 : 2
                ))
                .toList();
    }

    private Long memberIdOf(Room room, String participantId) {
        return room.member(participantId)
                .map(RoomMember::participant)
                .map(GameParticipant::memberId)
                .orElse(null);
    }
}
