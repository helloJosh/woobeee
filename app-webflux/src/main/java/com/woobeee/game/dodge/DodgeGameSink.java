package com.woobeee.game.dodge;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.FinishedParticipant;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.room.GameIdGenerator;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 순수한 {@link DodgeGame} 을 방·소켓·타이머와 이어 붙인다.
 *
 * <p>입력은 틱 사이에 버퍼에 쌓이고, 참가자당 마지막 것만 남는다. 타이머가 틱을 돌릴 때 버퍼를
 * 통째로 비워 게임에 넘긴다 — 이 구조 덕분에 "틱당 1회"가 자연히 지켜진다.
 *
 * <p>{@link GameCommandSink} 의 메서드들은 방 명령을 직렬화하는 큐 없이 호출된다 — 최대 8명의
 * 참가자가 보내는 이동 명령과, 방마다 하나씩 도는 틱 타이머가 전부 같은 {@link DodgeGame} 인스턴스를
 * 서로 다른 스레드에서 동시에 건드릴 수 있다({@code positions}/{@code obstacles}/
 * {@code eliminationOrder} 는 스레드 안전하지 않은 평범한 컬렉션이다). 그래서 그 게임 인스턴스를
 * 실제로 읽거나 바꾸는 코드(틱 진행, 이탈 처리, 종료 판정)는 전부 그 게임 객체 자체를 모니터로
 * 동기화해 직렬화한다 — {@link OmokGameSink} 와 같은 패턴이다.
 *
 * <p>종료 판정과 방별 맵(games/pendingInputs/recordedInputs/departuresByTick/displayNames/
 * memberIds/startedAt/timers) 퇴출은 그 동기화 블록 안에서 {@link Map#remove(Object, Object)} 로
 * 원자적으로 실행한다 — 같은 방에 대해 동시에 도착한 틱과 이탈 통지가 게임을 두 번 끝내 결과를
 * 두 번 남기는 일을 막는다. {@link #onParticipantGone} 은 마지막 한 명의 이탈을 포함해 모든 실제
 * 이탈에 대해 불리고, 게임이 이미 끝난 뒤에도 불릴 수 있다 — {@link DodgeGame#eliminate(String)}
 * 자체가 이미 끝난 게임에서는 아무 일도 하지 않는 멱등 연산이고, 종료 판정과 결과 기록은 오직
 * {@link #tick} 에서만 하므로 두 번째 이탈 통지가 결과를 다시 남기지 않는다.
 *
 * <p><b>이탈로 끝난 게임의 재생 가능성(F1).</b> {@link DodgeGame#eliminate(String)} 은
 * {@code advanceOneTick} 이 읽는 입력 스트림 밖에서 게임 상태를 바꾼다 — 이 싱크의 주된 종료
 * 경로가 바로 이것이다(참가자 전원이 이탈해 한 명만 남는 경우). 그 이탈을 기보에 싣지 않으면
 * {@link DodgeReplayRunner} 는 이탈한 참가자를 계속 살려 둔 채로 재생하게 되어 원본과 다른
 * 결과가 나온다. 그래서 {@code onParticipantGone} 은 이탈이 실제로 게임 상태를 바꿨을 때만(게임이
 * 아직 안 끝났고 그 참가자가 아직 생존해 있었을 때만) 그 순간의 틱 번호로
 * {@link #departuresByTick} 에 기록한다 — {@link DodgeReplay#departuresByTick()} 로 실려
 * {@link DodgeReplayRunner} 가 각 틱을 진행하기 전에 그대로 재현한다.
 */
@Component
public class DodgeGameSink implements GameCommandSink {
    private static final Logger log = LoggerFactory.getLogger(DodgeGameSink.class);

    private final Map<String, DodgeGame> games = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Direction>> pendingInputs = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Map<String, Direction>>> recordedInputs = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, List<String>>> departuresByTick = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> displayNames = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Long>> memberIds = new ConcurrentHashMap<>();
    private final Map<String, Instant> startedAt = new ConcurrentHashMap<>();
    private final Map<String, Disposable> timers = new ConcurrentHashMap<>();

    private final RoomHub roomHub;
    private final GameResultService gameResultService;
    private final DodgeReplayWriter replayWriter;
    private final GameIdGenerator idGenerator;
    private final Clock clock;
    private final Scheduler tickScheduler;

    public DodgeGameSink(
            RoomHub roomHub,
            GameResultService gameResultService,
            DodgeReplayWriter replayWriter,
            GameIdGenerator idGenerator,
            Clock clock,
            Scheduler gameTimerScheduler
    ) {
        this.roomHub = roomHub;
        this.gameResultService = gameResultService;
        this.replayWriter = replayWriter;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.tickScheduler = gameTimerScheduler;
    }

    @Override
    public GameType gameType() {
        return GameType.DODGE;
    }

    /** 테스트에서 상태를 들여다보기 위한 접근자. 프로덕션 코드는 부르지 않는다. */
    DodgeGame gameOf(String roomId) {
        return games.get(roomId);
    }

    /** 테스트에서 아직 소비되지 않은 입력을 들여다보기 위한 접근자. 프로덕션 코드는 부르지 않는다. */
    Direction pendingInputOf(String roomId, String participantId) {
        return pendingInputs.getOrDefault(roomId, Map.of()).get(participantId);
    }

    /**
     * 테스트에서 한 방에 대한 부수 상태가 <b>전부</b> 정리됐는지 한 번에 보기 위한 접근자.
     * 프로덕션 코드는 부르지 않는다. 맵이 하나 늘어날 때 여기에도 추가한다 — 종료 경로가
     * 예외로 중단돼 일부만 정리되는 누수를 잡는 것이 이 접근자의 목적이다.
     */
    boolean holdsAnyStateFor(String roomId) {
        return games.containsKey(roomId)
                || pendingInputs.containsKey(roomId)
                || recordedInputs.containsKey(roomId)
                || departuresByTick.containsKey(roomId)
                || displayNames.containsKey(roomId)
                || memberIds.containsKey(roomId)
                || startedAt.containsKey(roomId)
                || timers.containsKey(roomId);
    }

    /**
     * 테스트에서 방별 틱 타이머의 구독을 직접 들여다보기 위한 접근자. 프로덕션 코드는 부르지
     * 않는다. {@code gameOf(roomId)} 가 null 이 되는 것(={@link #tick} 이 {@code games} 맵에서
     * 뺀 것)과 실제로 타이머가 {@code dispose()} 된 것은 별개다 — 이 둘을 혼동하면 안 된다.
     */
    Disposable timerOf(String roomId) {
        return timers.get(roomId);
    }

    @Override
    public void onStart(Room room) {
        List<RoomMember> members = room.members();
        List<String> participantIds = members.stream()
                .map(member -> member.participant().participantId())
                .toList();

        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Long> memberIdsByParticipant = new LinkedHashMap<>();
        for (RoomMember member : members) {
            GameParticipant participant = member.participant();
            names.put(participant.participantId(), participant.displayName());
            // RoomCommandDispatcher.settle removes a departed participant from the room
            // before calling onParticipantGone, so by finish() time room.member(...) can no
            // longer resolve their memberId. Snapshot it here, while everyone is still
            // present, so a member who is eliminated by leaving still keeps their memberId
            // on their result row (and therefore their match history / replay access).
            if (participant.memberId() != null) {
                memberIdsByParticipant.put(participant.participantId(), participant.memberId());
            }
        }

        String roomId = room.roomId();
        games.put(roomId, new DodgeGame(participantIds, idGenerator.nextSeed()));
        pendingInputs.put(roomId, new ConcurrentHashMap<>());
        recordedInputs.put(roomId, new LinkedHashMap<>());
        departuresByTick.put(roomId, new LinkedHashMap<>());
        displayNames.put(roomId, names);
        memberIds.put(roomId, memberIdsByParticipant);
        startedAt.put(roomId, clock.instant());

        Duration period = Duration.ofMillis(DodgeRules.TICK_MILLIS);
        timers.put(roomId, Flux.interval(period, period, tickScheduler)
                .subscribe(
                        ignored -> onTick(room),
                        error -> log.error("Dodge tick loop for room {} terminated unexpectedly",
                                roomId, error)));
    }

    /**
     * 인터벌이 매 틱 부르는 진입점. 던져진 예외가 {@code Flux.interval} 구독까지 올라가면 안 된다 —
     * 거기서의 {@code onError} 는 시퀀스를 영구히 끝내고 다시 구독하는 코드가 없으므로, 그 방의
     * 게임은 아무 신호도 없이 그냥 멈춘다(에러 컨슈머조차 없으면 reactor 의 {@code onErrorDropped}
     * 로 사라진다). {@link com.woobeee.game.room.RoomSweeper#onTick()} 과 같은 패턴이다.
     *
     * <p>구체적인 위험은 {@link #finish} 에 있다: {@link DodgeReplayWriter} 는 Jackson 실패를
     * {@link IllegalStateException} 으로 감싸고, {@link GameResultService#record} 도 동기적으로
     * 던질 수 있다. 그 예외가 이 지점을 넘어가면 해당 방의 틱 루프가 통째로 죽는다.
     */
    void onTick(Room room) {
        try {
            tick(room);
        } catch (Exception e) {
            log.error("Dodge tick failed for room {}; the loop continues", room.roomId(), e);
        }
    }

    @Override
    public void onGameCommand(Room room, String participantId, ClientMessage message) {
        if (!"DODGE_MOVE".equals(message.type())) {
            return;
        }

        Direction direction = Direction.parse(
                message.payload() == null ? null : message.payload().path("direction").asString(null)
        );
        if (direction == null) {
            return;
        }

        Map<String, Direction> buffer = pendingInputs.get(room.roomId());
        if (buffer != null) {
            buffer.put(participantId, direction);
        }
    }

    /**
     * 재접속한 참가자에게 화면을 다시 그려 줄 GAME_SNAPSHOT 을 낸다.
     *
     * <p>내용은 평소 {@code DODGE_TICK} 이 싣는 것과 같은 프레임({@code tick}/{@code positions}/
     * {@code obstacles})이다 — 클라이언트의 기존 프레임 렌더러가 그대로 처리한다.
     * {@link DodgeGame#currentFrame()} 은 틱을 진행하지 않으므로, 재접속 때문에 게임이 한 칸
     * 앞으로 가는 일은 없다.
     *
     * <p><b>브로드캐스트를 모니터 안에서 한다.</b> 프레임만 모니터 안에서 뜨고 밖에서 내보내면, 그
     * 사이에 틱 루프가 모니터를 잡고 N+1 로 진행해 {@code DODGE_TICK} 을 먼저 내보낼 수 있다.
     * 그러면 뒤늦게 나가는 이 스냅샷은 {@code tick=N} 짜리 낡은 전체 상태가 되고, 방 전체가 한 프레임
     * 되감긴 뒤 그 위에 N+2 를 얹는다. {@link RoomHub} 가 emit 을 방마다 직렬화하지만 페이로드는 그
     * 락을 잡기 전에 계산되므로 도움이 되지 않는다. 허브가 게임 모니터를 잡는 경로는 어디에도 없어
     * (구독자는 세션 전송뿐이다) 이 순서로 인한 교착은 생기지 않는다.
     *
     * <p><b>세션 하나가 아니라 방 전체에 브로드캐스트한다.</b> {@link RoomHub} 에는 세션 단위 전송이
     * 없고({@code subscribe(roomId)} / {@code broadcast(roomId, message)} 뿐이다) 그것을 추가하는
     * 것은 이 변경의 범위를 넘는다. 나머지 참가자가 권위 있는 상태로 한 번 더 그리는 것은 무해하고,
     * 오히려 그동안 벌어진 어긋남을 스스로 바로잡는다.
     */
    @Override
    public void onRejoin(Room room, String participantId) {
        DodgeGame game = games.get(room.roomId());
        if (game == null) {
            return;
        }

        // 프레임을 뜨는 것과 내보내는 것을 틱 루프와 같은 모니터 안에 함께 둔다 — positions/
        // obstacles 가 스레드 안전하지 않기도 하지만, 무엇보다 뜬 시점과 나가는 시점 사이에 틱이
        // 돌면 스냅샷이 낡은 전체 상태가 된다(위 javadoc 참조).
        synchronized (game) {
            if (game.finished()) {
                return;
            }

            DodgeFrame frame = game.currentFrame();
            roomHub.broadcast(room.roomId(), ServerMessage.of("GAME_SNAPSHOT", Map.of(
                    "gameType", gameType().name(),
                    "tick", frame.tick(),
                    "positions", positionsOf(frame),
                    "obstacles", obstaclesOf(frame)
            )));
        }
    }

    @Override
    public void onParticipantGone(Room room, String participantId) {
        String roomId = room.roomId();
        DodgeGame game = games.get(roomId);
        if (game == null) {
            return;
        }

        // Guard against a tick concurrently reading/advancing the same DodgeGame instance —
        // positions/obstacles/eliminationOrder are plain (non-thread-safe) collections.
        // eliminate() itself is a no-op once the game is finished (or for an unknown
        // participant), which is what makes a second departure notification, or one that
        // arrives after the game has already ended, harmless here. The actual end-of-game
        // decision and result recording only ever happen from tick() below.
        synchronized (game) {
            // Only an eliminate() call that actually changes state is a real departure worth
            // replaying — recording a no-op (already finished, already gone, unknown id) would
            // make DodgeReplayRunner try to eliminate an already-absent participant, which is
            // harmless but pollutes the replay with an event that never really happened live.
            boolean isARealDeparture = !game.finished() && game.survivors().contains(participantId);
            int tickOfDeparture = game.tick();

            game.eliminate(participantId);

            if (isARealDeparture) {
                Map<Integer, List<String>> departures = departuresByTick.get(roomId);
                if (departures != null) {
                    departures.computeIfAbsent(tickOfDeparture, ignored -> new ArrayList<>())
                            .add(participantId);
                }
            }
        }
    }

    private void tick(Room room) {
        String roomId = room.roomId();
        DodgeGame game = games.get(roomId);
        if (game == null) {
            return;
        }

        DodgeFrame frame;
        boolean justFinished;
        synchronized (game) {
            // Drain by removing each currently-present key individually (each pendingInputs
            // value is a ConcurrentHashMap) rather than copy-then-clear: a copy followed by a
            // clear has a window between the two where an onGameCommand put for a brand-new
            // key would be wiped by the clear without ever being read by this tick — silently
            // dropped rather than merely deferred to the next one. Removing key-by-key means a
            // key not yet present when we snapshot the key set is untouched here and survives
            // for the next tick's drain instead.
            Map<String, Direction> buffer = pendingInputs.get(roomId);
            Map<String, Direction> inputs;
            if (buffer == null) {
                inputs = Map.of();
            } else {
                inputs = new LinkedHashMap<>();
                for (String participantId : List.copyOf(buffer.keySet())) {
                    Direction direction = buffer.remove(participantId);
                    if (direction != null) {
                        inputs.put(participantId, direction);
                    }
                }
            }

            // A game already finished by a departure (see onParticipantGone) makes
            // advanceOneTick a no-op: it returns immediately without incrementing the tick
            // counter. Recording drained input against a tick that never actually executed
            // would leave a "moves" line in the ndjson for a tick that has no corresponding
            // real advance -- ghost data no reader asked for. Only record when this call is
            // the one that actually moved the game forward.
            boolean alreadyFinishedBeforeThisTick = game.finished();
            int tickBeforeAdvance = game.tick();
            frame = game.advanceOneTick(inputs);

            if (!alreadyFinishedBeforeThisTick && !inputs.isEmpty()) {
                Map<Integer, Map<String, Direction>> recorded = recordedInputs.get(roomId);
                if (recorded != null) {
                    recorded.put(tickBeforeAdvance, inputs);
                }
            }

            // Evict the game from the map inside the same synchronized block that decided
            // it finished, using the atomic remove(key, value) form: only the caller that
            // wins this race sees justFinished=true, so finish() below runs exactly once
            // even if a concurrent onParticipantGone() is also holding/waiting on this
            // monitor around the same moment.
            justFinished = frame.finished() && games.remove(roomId, game);
        }

        roomHub.broadcast(roomId, ServerMessage.of("DODGE_TICK", Map.of(
                "tick", frame.tick(),
                "positions", positionsOf(frame),
                "obstacles", obstaclesOf(frame),
                "eliminated", frame.eliminatedThisTick()
        )));

        if (justFinished) {
            finish(room, game);
        }
    }

    /**
     * GAME_END 는 즉시, 저장과 무관하게 나간다 — {@link GameResultService#record} 는 DB 기록에
     * 이어 기보를 S3 에 올린 뒤에야 완료되므로, 그 결과(gameResultId)를 기다렸다가 방송하면
     * 참가자들이 승자를 알기까지 스토리지 왕복(및 타임아웃 가능성)을 떠안게 된다. 그래서 GAME_END
     * 페이로드에는 gameResultId 가 없고, winnerParticipantId 와 ranks 만 실어 먼저 보낸 뒤 record
     * 호출은 구독만 해 둔다.
     *
     * <p>호출 시점에 {@code game} 은 이미 {@link #tick} 이 {@code games} 맵에서 원자적으로 뺀
     * 뒤다 — 그래도 동시에 도착한 {@link #onParticipantGone} 호출이 이 게임 객체 자체를 여전히
     * 들고 있다가 뒤늦게 {@code eliminate} 를 부를 수 있으므로(무해한 멱등 호출이다), 여기서
     * 게임 상태를 읽는 부분도 그 게임 객체를 모니터로 동기화해 일관된 스냅샷을 본다.
     */
    private void finish(Room room, DodgeGame game) {
        String roomId = room.roomId();

        Disposable timer = timers.remove(roomId);
        if (timer != null) {
            timer.dispose();
        }

        // 방별 부수 상태 퇴출은 finally 에 둔다 — 아래에서 기보 직렬화(Jackson 실패를
        // IllegalStateException 으로 감싼다)나 record 가 동기적으로 던지면, 그 예외는
        // onTick 이 잡아 루프는 살아남지만 정리 코드는 실행되지 않은 뒤다. 그러면 끝난 방의
        // 맵 항목이 프로세스가 죽을 때까지 남는다. 정리는 종료 경로의 성패와 무관해야 한다.
        try {
            recordAndBroadcastEnd(room, game, roomId);
        } finally {
            pendingInputs.remove(roomId);
            recordedInputs.remove(roomId);
            departuresByTick.remove(roomId);
            displayNames.remove(roomId);
            memberIds.remove(roomId);
            startedAt.remove(roomId);
        }
    }

    private void recordAndBroadcastEnd(Room room, DodgeGame game, String roomId) {
        Map<String, String> names = displayNames.getOrDefault(roomId, Map.of());
        Map<String, Long> memberIdsByParticipant = memberIds.getOrDefault(roomId, Map.of());

        Map<String, Integer> ranks;
        int seed;
        Map<Integer, Map<String, Direction>> inputsByTick;
        Map<Integer, List<String>> departures;
        synchronized (game) {
            ranks = game.finalRanks();
            seed = game.seed();
            inputsByTick = recordedInputs.getOrDefault(roomId, Map.of());
            departures = departuresByTick.getOrDefault(roomId, Map.of());
        }

        String winner = ranks.entrySet().stream()
                .filter(entry -> entry.getValue() == 1)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        roomHub.broadcast(roomId, ServerMessage.of("GAME_END", Map.of(
                "winnerParticipantId", winner == null ? "" : winner,
                "ranks", ranksPayload(ranks, names)
        )));

        List<FinishedParticipant> participants = ranks.entrySet().stream()
                .map(entry -> new FinishedParticipant(
                        entry.getKey(),
                        names.getOrDefault(entry.getKey(), entry.getKey()),
                        memberIdsByParticipant.get(entry.getKey()),
                        entry.getValue()
                ))
                .toList();

        FinishedGame finished = new FinishedGame(
                "DODGE",
                roomId,
                startedAt.getOrDefault(roomId, clock.instant()),
                clock.instant(),
                winner,
                participants
        );

        DodgeReplay replay = new DodgeReplay(seed, new ArrayList<>(names.keySet()), inputsByTick, departures);
        String ndjson = replayWriter.toNdjson(replay, names);

        gameResultService.record(finished, ndjson).subscribe(
                gameResultId -> { },
                error -> log.error("Failed to record dodge result for room {}", roomId, error)
        );
    }

    private List<Map<String, Object>> positionsOf(DodgeFrame frame) {
        List<Map<String, Object>> positions = new ArrayList<>();
        frame.positions().forEach((participantId, cell) -> positions.add(Map.of(
                "participantId", participantId,
                "x", cell.x(),
                "y", cell.y()
        )));
        return positions;
    }

    private List<Map<String, Object>> obstaclesOf(DodgeFrame frame) {
        return frame.obstacles().stream()
                .map(cell -> Map.<String, Object>of("x", cell.x(), "y", cell.y()))
                .toList();
    }

    private List<Map<String, Object>> ranksPayload(Map<String, Integer> ranks, Map<String, String> names) {
        return ranks.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "participantId", entry.getKey(),
                        "displayName", names.getOrDefault(entry.getKey(), entry.getKey()),
                        "rank", entry.getValue()
                ))
                .toList();
    }
}
