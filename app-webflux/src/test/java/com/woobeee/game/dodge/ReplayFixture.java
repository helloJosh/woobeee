package com.woobeee.game.dodge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 커밋된 기보 픽스처 하나를 만들고, 그것을 재생한 결과를 <b>언어 중립적인 텍스트</b>로 적는다.
 *
 * <p>존재 이유는 {@code DodgeReplayWriter} 의 출력과 브라우저 리더
 * ({@code front/lib/dodge-engine.ts} 의 {@code parseReplayNdjson}) 사이에 실제 파일이 오가는
 * 왕복을 만드는 것이다. 그 전까지는 자바 쪽에 작성기 단위 검사가, 프론트 쪽에 자체 골든이
 * 따로 있었을 뿐 <b>작성기가 쓴 바이트를 리더가 읽는 경로</b>는 어느 테스트도 지나지 않았다 —
 * 그래서 리더의 헤더 파싱이 조용히 다른 게임을 그려도 두 스위트가 모두 초록이었다.
 *
 * <p>산출물 두 개는 {@code src/test/resources/replay/} 에 커밋돼 있다.
 * <ul>
 *   <li>{@code dodge-replay-v3.ndjson} — 진짜 {@link DodgeReplayWriter} 가 쓴 파일
 *   <li>{@code dodge-replay-v3.trace.txt} — 그 기보를 재생한 프레임 자취
 * </ul>
 * {@code DodgeReplayWriterTest} 가 두 파일을 자바 쪽에서 재생산해 대조하고,
 * {@code front/lib/dodge-replay-roundtrip.test.ts} 가 <b>같은 두 파일</b>을 읽어 타입스크립트
 * 엔진으로 같은 자취를 만들어 낸다. 어느 한쪽 엔진이 흔들리면 그쪽 테스트가 깨진다.
 *
 * <p>포맷이 정말 바뀌어야 한다면 두 파일을 다시 만들어야 한다 — 그 재생성이 곧 "리더도 같이
 * 봤다" 는 확인 절차다.
 */
final class ReplayFixture {

    static final String NDJSON_NAME = "dodge-replay-v3.ndjson";
    static final String TRACE_NAME = "dodge-replay-v3.trace.txt";

    /** 한글 이름 하나를 섞어 둔다 — 파일이 UTF-8 로 오가는 것까지 픽스처가 고정한다. */
    static final Map<String, String> DISPLAY_NAMES = Map.of(
            "m:11", "host",
            "g:a", "손님",
            "g:b", "guest-b",
            "g:c", "guest-c"
    );

    static final List<String> PLAYERS = List.of("m:11", "g:a", "g:b", "g:c");
    static final int SEED = 8412739;
    /** 이 틱에 g:c 가 방을 나간다 — 입력 스트림 밖에서 상태가 바뀌는 사건(v2 의 departures). */
    static final int DEPARTURE_TICK = 3;

    private static final Direction[] CYCLE = {
            Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN
    };

    private ReplayFixture() {
    }

    /**
     * 실제로 한 판을 끝까지 두어 기보를 만든다. 손으로 지어낸 입력 맵이 아니라 진짜 게임의
     * 기록이어야, 재생이 원본과 같은지를 묻는 것이 의미가 있다.
     *
     * <p>{@link #DEPARTURE_TICK} 이후로도 떠난 참가자에 대한 입력이 계속 기록된다 — 일부러
     * 그대로 둔다. 두 엔진 모두 "판 위에 없는 참가자의 입력" 을 조용히 건너뛰어야 하고,
     * 그 동작이 어긋나면 자취가 갈린다.
     */
    static DodgeReplay build() {
        DodgeGame game = new DodgeGame(PLAYERS, SEED);
        Map<Integer, Map<String, Direction>> inputs = new LinkedHashMap<>();
        Map<Integer, List<String>> departures = new LinkedHashMap<>();

        while (!game.finished() && game.tick() < 500) {
            int tick = game.tick();

            if (tick == DEPARTURE_TICK) {
                departures.put(tick, List.of("g:c"));
                game.eliminate("g:c");
                if (game.finished()) {
                    break;
                }
            }

            Map<String, Direction> tickInputs = new LinkedHashMap<>();
            for (int i = 0; i < PLAYERS.size(); i++) {
                if ((tick + i) % 3 == 0) {
                    tickInputs.put(PLAYERS.get(i), CYCLE[(tick + i) % CYCLE.length]);
                }
            }
            if (!tickInputs.isEmpty()) {
                inputs.put(tick, tickInputs);
            }

            game.advanceOneTick(tickInputs);
        }

        return new DodgeReplay(SEED, PLAYERS, inputs, departures);
    }

    /**
     * 기보를 재생하며 매 틱의 프레임을 한 줄씩 적고, 마지막에 요약 한 줄을 붙인다.
     *
     * <p>순서는 {@code DodgeReplayRunner.rerun} / 프론트의 {@code stepReplay} 와 같다:
     * 그 틱의 이탈을 먼저 반영하고, 그것으로 게임이 끝났으면 {@code advanceOneTick} 을
     * 건너뛴다. 여기서 러너를 그대로 부르지 않는 이유는 러너가 <b>마지막 게임</b>만 돌려주고
     * 중간 프레임을 내주지 않기 때문이다 — 자취의 값어치는 매 틱의 좌표에 있다.
     *
     * <p>줄 모양은 프론트 테스트가 글자 하나까지 같은 것을 만들어 내야 하므로 계약이다.
     */
    static String traceOf(DodgeReplay replay) {
        DodgeGame game = new DodgeGame(replay.participantIds(), replay.seed());
        StringBuilder trace = new StringBuilder();

        while (!game.finished() && game.tick() < 100_000) {
            int tick = game.tick();

            for (String participantId : replay.departuresByTick().getOrDefault(tick, List.of())) {
                game.eliminate(participantId);
            }
            if (game.finished()) {
                break;
            }

            trace.append(renderFrame(game.advanceOneTick(
                    replay.inputsByTick().getOrDefault(tick, Map.of())))).append('\n');
        }

        trace.append("final ticks=").append(game.tick())
                .append(" ranks=").append(renderRanks(replay.participantIds(), game.finalRanks()))
                .append('\n');
        return trace.toString();
    }

    private static String renderFrame(DodgeFrame frame) {
        return "t" + frame.tick()
                + " pos=" + renderPositions(frame.positions())
                + " obs=" + renderObstacles(frame.obstacles())
                + " elim=" + String.join("|", frame.eliminatedThisTick())
                + " fin=" + (frame.finished() ? 1 : 0);
    }

    private static String renderPositions(Map<String, Cell> positions) {
        List<String> parts = new ArrayList<>();
        positions.forEach((participantId, cell) ->
                parts.add(participantId + "@" + cell.x() + "," + cell.y()));
        return String.join("|", parts);
    }

    private static String renderObstacles(List<Obstacle> obstacles) {
        List<String> parts = new ArrayList<>();
        for (Obstacle obstacle : obstacles) {
            parts.add(obstacle.x() + "," + obstacle.y() + "," + obstacle.w() + "," + obstacle.h());
        }
        return String.join("|", parts);
    }

    /** 참가자 순서는 헤더의 players 순서로 고정한다 — 맵 순회 순서에 기대지 않는다. */
    private static String renderRanks(List<String> participantIds, Map<String, Integer> ranks) {
        List<String> parts = new ArrayList<>();
        for (String participantId : participantIds) {
            parts.add(participantId + "=" + ranks.get(participantId));
        }
        return String.join("|", parts);
    }
}
