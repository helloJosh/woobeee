package com.woobeee.game.omok;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>{@link GameCommandSink} 의 메서드들은 방 명령을 직렬화하는 큐 없이 호출된다 — 같은 방에
 * 여러 세션이 동시에 명령을 보내면 이 클래스의 메서드들도 서로 다른 스레드에서 동시에 불릴 수
 * 있다. {@link Room} 자체는 스레드 안전하지만(그 자신을 모니터로 동기화한다), 방마다 이 클래스가
 * 따로 들고 있는 {@link OmokGame} 은 그렇지 않다. 그래서 한 게임의 상태를 읽거나 바꾸는 모든
 * 코드({@code place}/{@code resign} 호출과 그 결과로 읽는 필드들)는 그 게임 인스턴스 자체를
 * 모니터로 동기화해 직렬화한다 — 같은 방에 대해 동시에 도착한 두 착수가 판을 동시에 건드리거나,
 * 자진 기권과 착수가 동시에 승리 판정을 통과해 결과를 두 번 남기는 일을 막는다.
 */
@Component
public class OmokGameSink implements GameCommandSink {
    private static final Logger log = LoggerFactory.getLogger(OmokGameSink.class);
    public static final Duration MOVE_LIMIT = Duration.ofSeconds(60);

    private final Map<String, OmokGame> games = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> displayNames = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> memberIds = new ConcurrentHashMap<>();

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
        Map<String, Long> ids = new LinkedHashMap<>();
        members.forEach(member -> {
            names.put(member.participant().participantId(), member.participant().displayName());
            // RoomCommandDispatcher.settle removes a departed participant from the room
            // before calling onParticipantGone, so by finish() time room.member(...) can
            // no longer resolve their memberId. Snapshot it here, while everyone is still
            // present, so a member who resigns by leaving still keeps their memberId on
            // their result row (and therefore their match history / replay access).
            ids.put(member.participant().participantId(), member.participant().memberId());
        });
        displayNames.put(room.roomId(), names);
        memberIds.put(room.roomId(), ids);
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

        PlaceOutcome outcome;
        String nextTurn = null;
        synchronized (game) {
            outcome = game.place(participantId, x, y, clock.instant());
            if (outcome.status() == PlaceOutcome.Status.PLACED) {
                nextTurn = game.currentTurnParticipantId();
            }
        }

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
                            "nextTurn", nextTurn,
                            "turnDeadline", outcome.turnDeadline().toString()
                    )
            ));
            // 승리 착수도 착수 성공이다 — 돌은 이미 놓였고 moves 에도 기록됐다. OMOK_MOVED 로
            // 마지막 돌의 좌표를 먼저 알리고, 그다음 finish() 가 GAME_END 를 보낸다. GAME_END
            // 페이로드에는 좌표가 없으므로(승자·순위만 싣는다) 이 순서를 지키지 않으면 클라이언트
            // 판에 승리한 돌이 영영 그려지지 않는다.
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

    /**
     * 재접속한 참가자에게 판을 다시 그려 줄 GAME_SNAPSHOT 을 낸다.
     *
     * <p>판을 격자로 인코딩하지 않고 착수 목록을 순서대로 싣는다. 각 착수가 자기 색을 함께 들고
     * 있어서(<code>{x, y, color}</code>) 클라이언트는 별도의 헤더 없이 목록만으로 판을 세울 수 있다.
     * <b>이것은 기보 형식이 아니다</b> — {@link OmokReplayWriter} 는 색을 헤더의
     * {@code players[]} 에 한 번만 선언하고 각 수는 <code>{t, p, x, y}</code> 로 적는다. 재생은
     * 헤더부터 순서대로 읽는 것이 전제라 그 모양이 맞지만, 판 하나를 즉시 그리는 데에는 자기 자신을
     * 설명하는 쪽이 맞다. 두 형식이 다른 것은 의도된 것이다.
     *
     * <p><b>브로드캐스트를 모니터 안에서 한다.</b> 페이로드만 모니터 안에서 만들고 밖에서 내보내면,
     * 그 사이에 착수 하나가 모니터를 잡고 N+1 번째 수를 두고 {@code OMOK_MOVED} 를 먼저 내보낼 수
     * 있다. 그러면 뒤늦게 나가는 이 스냅샷은 {@code [1..N]} 만 담은 낡은 전체 상태가 되고, 방 전체가
     * 그걸 받아 N+1 을 영영 잃은 채 되감긴다 — 차례도 어긋나고 다음 수는 갈라진 판에 놓인다.
     * {@link RoomHub} 가 emit 을 방마다 직렬화하지만 페이로드는 그 락을 잡기 전에 계산되므로 도움이
     * 되지 않는다. 허브가 게임 모니터를 잡는 경로는 어디에도 없어(구독자는 세션 전송뿐이다) 이
     * 순서로 인한 교착은 생기지 않는다.
     *
     * <p><b>세션 하나가 아니라 방 전체에 브로드캐스트한다.</b> {@link RoomHub} 에는 세션 단위 전송이
     * 없고({@code subscribe(roomId)} / {@code broadcast(roomId, message)} 뿐이다) 그것을 추가하는
     * 것은 이 변경의 범위를 넘는다. 나머지 참가자가 권위 있는 상태로 한 번 더 그리는 것은 무해하고,
     * 오히려 그동안 벌어진 어긋남을 스스로 바로잡는다.
     */
    @Override
    public void onRejoin(Room room, String participantId) {
        OmokGame game = games.get(room.roomId());
        if (game == null) {
            return;
        }

        // 읽기와 내보내기를 착수와 같은 모니터 안에 함께 둔다 — 상태를 읽은 시점과 그것이 실제로
        // 나가는 시점 사이에 판이 움직이면 스냅샷은 낡은 전체 상태가 된다(위 javadoc 참조).
        synchronized (game) {
            if (game.finished()) {
                return;
            }

            List<Map<String, Object>> moves = game.moves().stream()
                    .map(move -> Map.<String, Object>of(
                            "x", move.x(),
                            "y", move.y(),
                            "color", move.stone().name()
                    ))
                    .toList();

            roomHub.broadcast(room.roomId(), ServerMessage.of("GAME_SNAPSHOT", Map.of(
                    "gameType", gameType().name(),
                    "moves", moves,
                    "nextTurn", game.currentTurnParticipantId(),
                    "turnDeadline", game.turnDeadline().toString()
            )));
        }
    }

    @Override
    public void onParticipantGone(Room room, String participantId) {
        OmokGame game = games.get(room.roomId());
        if (game == null) {
            return;
        }

        PlaceOutcome outcome;
        synchronized (game) {
            if (game.finished()) {
                return;
            }
            outcome = game.resign(participantId);
        }

        if (outcome.status() == PlaceOutcome.Status.WIN) {
            finish(room, game, outcome.winnerParticipantId());
        }
    }

    /**
     * GAME_END 는 즉시, 저장과 무관하게 나간다 — {@link GameResultService#record} 는 DB 기록에
     * 이어 기보를 S3 에 올린 뒤에야 완료되므로, 그 결과(예: gameResultId)를 기다렸다가 방송하면
     * 참가자들이 승자를 알기까지 스토리지 왕복(및 타임아웃 가능성)을 떠안게 된다. 그래서 GAME_END
     * 페이로드는 winnerParticipantId 와 ranks 만 싣고, record 호출은 방송 뒤에 구독만 해 둔다.
     */
    private void finish(Room room, OmokGame game, String winnerParticipantId) {
        // 재대국(GAME-AC-30)은 방이 FINISHED 일 때만 열린다. 이 전환이 없으면 방은 6시간
        // TTL 까지 IN_PROGRESS 로 남는다.
        room.finishGame();

        Map<String, String> names = displayNames.getOrDefault(room.roomId(), Map.of());
        Map<String, Long> ids = memberIds.getOrDefault(room.roomId(), Map.of());
        Instant start = startedAt.getOrDefault(room.roomId(), clock.instant());

        List<Map<String, Object>> ranks;
        List<FinishedParticipant> participants;
        String ndjson;
        synchronized (game) {
            ranks = ranksOf(game, winnerParticipantId, names);
            participants = participantsOf(game, winnerParticipantId, names, ids);
            ndjson = replayWriter.toNdjson(game, names);
        }

        roomHub.broadcast(room.roomId(), ServerMessage.of("GAME_END", Map.of(
                "winnerParticipantId", winnerParticipantId == null ? "" : winnerParticipantId,
                "ranks", ranks
        )));

        FinishedGame finished = new FinishedGame(
                "OMOK",
                room.roomId(),
                start,
                clock.instant(),
                winnerParticipantId,
                participants
        );

        gameResultService.record(finished, ndjson).subscribe(
                gameResultId -> { },
                error -> log.error("Failed to record omok result for room {}", room.roomId(), error)
        );

        games.remove(room.roomId(), game);
        startedAt.remove(room.roomId());
        displayNames.remove(room.roomId());
        memberIds.remove(room.roomId());
    }

    private List<Map<String, Object>> ranksOf(OmokGame game, String winner, Map<String, String> names) {
        String loser = game.opponentOf(winner);
        return List.of(
                Map.of("participantId", winner, "displayName", names.getOrDefault(winner, winner), "rank", 1),
                Map.of("participantId", loser, "displayName", names.getOrDefault(loser, loser), "rank", 2)
        );
    }

    private List<FinishedParticipant> participantsOf(
            OmokGame game,
            String winner,
            Map<String, String> names,
            Map<String, Long> memberIds
    ) {
        return List.of(game.blackParticipantId(), game.whiteParticipantId()).stream()
                .map(id -> new FinishedParticipant(
                        id,
                        names.getOrDefault(id, id),
                        memberIds.get(id),
                        id.equals(winner) ? 1 : 2
                ))
                .toList();
    }
}
