package com.woobeee.game.dodge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장애물피하기 한 판. 순수 자바다 — 시계도 소켓도 모른다.
 *
 * <p>{@link #advanceOneTick(Map)} 만이 상태를 움직인다. 이 함수가 순수하기 때문에 기보 재생이
 * 원본과 같은 결과를 낸다는 것을 테스트로 증명할 수 있다.
 *
 * <p>테스트 전용 상태(시작 위치·장애물·스폰 확률)는 별도의 {@code ...ForTest} 메서드가 아니라
 * 패키지 전용 생성자의 인자로 주입한다. 그래야 프로덕션 경로에서만 쓰이는 두 개의 생성자
 * ({@link #DodgeGame(List, int)} 는 공개, 나머지 하나는 패키지 전용)만 남고, 이 클래스에는
 * 테스트만을 위한 가변(mutator) 메서드가 하나도 없다.
 */
public final class DodgeGame {
    private final List<String> participantIds;
    private final Map<String, Cell> positions;
    private final List<List<String>> eliminationOrder = new ArrayList<>();
    private final Xorshift32 random;
    private final int seed;
    /** null 이면 {@link DodgeRules#spawnProbability(int)} 를 그대로 쓴다. 0.0 을 넘기면 스폰이 없다. */
    private final Double spawnProbabilityOverride;

    private List<Cell> obstacles;
    private int tick;
    private boolean finished;

    /** 실제 게임 생성자. 시작 위치는 {@link DodgeRules#startingCells(int)} 가 정하고, 장애물은 없이 시작한다. */
    public DodgeGame(List<String> participantIds, int seed) {
        this(participantIds, seed, defaultStartingPositions(participantIds), List.of(), null);
    }

    /**
     * 시나리오 생성자. 시작 위치·장애물·스폰 확률을 직접 지정한다.
     *
     * <p>테스트가 이동/충돌/순위 로직을 각각 격리해서 검증할 때 쓴다. {@code spawnProbabilityOverride}
     * 에 0.0 을 넘기면 장애물이 절대 새로 생기지 않는 "조용한" 게임이 된다.
     */
    DodgeGame(List<String> participantIds,
              int seed,
              Map<String, Cell> startingPositions,
              List<Cell> startingObstacles,
              Double spawnProbabilityOverride) {
        this.participantIds = List.copyOf(participantIds);
        this.seed = seed;
        this.random = new Xorshift32(seed);
        this.positions = new LinkedHashMap<>(startingPositions);
        this.obstacles = new ArrayList<>(startingObstacles);
        this.spawnProbabilityOverride = spawnProbabilityOverride;
    }

    private static Map<String, Cell> defaultStartingPositions(List<String> participantIds) {
        List<Cell> starts = DodgeRules.startingCells(participantIds.size());
        Map<String, Cell> positions = new LinkedHashMap<>();
        for (int i = 0; i < participantIds.size(); i++) {
            positions.put(participantIds.get(i), starts.get(i));
        }
        return positions;
    }

    public int seed() {
        return seed;
    }

    public int tick() {
        return tick;
    }

    public boolean finished() {
        return finished;
    }

    public List<String> survivors() {
        return List.copyOf(positions.keySet());
    }

    public List<List<String>> eliminationOrder() {
        return List.copyOf(eliminationOrder);
    }

    /**
     * 틱 하나를 진행한다. 순서가 규칙이다:
     * 입력 반영 → 장애물 하강 → 신규 생성 → 충돌 판정 → 프레임 반환.
     */
    public DodgeFrame advanceOneTick(Map<String, Direction> inputs) {
        if (finished) {
            return frame(List.of());
        }

        Map<String, Cell> previous = new HashMap<>(positions);

        applyInputs(inputs);

        List<Cell> fallen = new ArrayList<>(obstacles.size());
        for (Cell obstacle : obstacles) {
            int y = obstacle.y() + 1;
            if (y < DodgeRules.ROWS) {
                fallen.add(new Cell(obstacle.x(), y));
            }
        }

        double probability = spawnProbabilityOverride != null
                ? spawnProbabilityOverride
                : DodgeRules.spawnProbability(tick);
        // 컬럼 0부터 오름차순으로 굴린다 — 순서가 난수열의 일부라 브라우저 기보 재생이
        // 같은 결과를 내려면 순서를 바꾸면 안 된다.
        for (int x = 0; x < DodgeRules.COLUMNS; x++) {
            if (random.nextDouble() < probability) {
                fallen.add(new Cell(x, 0));
            }
        }

        obstacles = fallen;

        List<String> eliminated = detectCollisions(previous);
        eliminated.forEach(positions::remove);
        if (!eliminated.isEmpty()) {
            eliminationOrder.add(List.copyOf(eliminated));
        }

        tick++;
        // 아무도 안 남으면 무조건 끝이다. 한 명만 남는 것은 원래 둘 이상으로 시작한
        // 게임에서만 "생존"의 의미가 있다 — 1인 게임은 시작부터 이미 한 명이었다.
        if (positions.isEmpty() || (participantIds.size() > 1 && positions.size() <= 1)) {
            finished = true;
        }

        return frame(eliminated);
    }

    /**
     * 충돌 판정. 같은 칸에 있으면 당연히 충돌이고, <b>서로 지나친 경우도 충돌</b>이다.
     *
     * <p>참가자가 위로 올라가고 장애물이 내려오면 격자 위에서는 겹치지 않지만 실제로는 부딪혔다.
     * 이 케이스를 빼면 위로 이동해 장애물을 통과하는 버그가 된다.
     */
    private List<String> detectCollisions(Map<String, Cell> previousPositions) {
        List<String> eliminated = new ArrayList<>();

        for (Map.Entry<String, Cell> entry : positions.entrySet()) {
            Cell now = entry.getValue();
            Cell before = previousPositions.get(entry.getKey());

            boolean hit = obstacles.stream().anyMatch(obstacle -> {
                if (obstacle.equals(now)) {
                    return true;
                }
                // 스왑: 장애물의 직전 위치(한 칸 위)가 참가자의 현재 자리이고,
                // 참가자의 직전 위치가 장애물의 현재 자리다.
                Cell obstacleBefore = new Cell(obstacle.x(), obstacle.y() - 1);
                return obstacleBefore.equals(now) && obstacle.equals(before);
            });

            if (hit) {
                eliminated.add(entry.getKey());
            }
        }

        return eliminated;
    }

    private void applyInputs(Map<String, Direction> inputs) {
        for (Map.Entry<String, Direction> entry : inputs.entrySet()) {
            Cell current = positions.get(entry.getKey());
            Direction direction = entry.getValue();
            if (current == null || direction == null) {
                continue;
            }

            int x = current.x() + direction.dx();
            int y = current.y() + direction.dy();
            if (x < 0 || x >= DodgeRules.COLUMNS || y < 0 || y >= DodgeRules.ROWS) {
                continue;
            }

            positions.put(entry.getKey(), new Cell(x, y));
        }
    }

    /** 이탈 확정. 그 틱의 탈락자로 기록하고 마지막 한 명이 남으면 게임을 끝낸다. */
    public void eliminate(String participantId) {
        if (finished || positions.remove(participantId) == null) {
            return;
        }

        eliminationOrder.add(List.of(participantId));
        if (positions.size() <= 1) {
            finished = true;
        }
    }

    /** 탈락 역순이 순위다. 같은 틱에 탈락한 사람은 공동 순위. */
    public Map<String, Integer> finalRanks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();

        List<String> alive = survivors();
        int nextRank = 1;
        if (!alive.isEmpty()) {
            alive.forEach(id -> ranks.put(id, 1));
            nextRank = 1 + alive.size();
        }

        for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
            List<String> bucket = eliminationOrder.get(i);
            for (String id : bucket) {
                ranks.put(id, nextRank);
            }
            nextRank += bucket.size();
        }

        return ranks;
    }

    private DodgeFrame frame(List<String> eliminated) {
        // Map.copyOf 는 불변 맵을 돌려주지만 순회 순서는 실행마다 바뀌는 해시 솔트에 좌우된다.
        // 리플레이 비교가 positions 를 직렬화해서 보므로, 순서가 실행마다 달라지면 안 된다 —
        // LinkedHashMap 을 복사해 삽입 순서를 그대로 굳힌 뒤 수정 불가 뷰로 감싼다.
        return new DodgeFrame(
                tick,
                Collections.unmodifiableMap(new LinkedHashMap<>(positions)),
                List.copyOf(obstacles),
                List.copyOf(eliminated),
                finished
        );
    }
}
