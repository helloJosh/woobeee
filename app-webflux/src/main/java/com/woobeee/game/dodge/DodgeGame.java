package com.woobeee.game.dodge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장애물피하기 한 판. 순수 자바다 — 시계도 소켓도 모른다.
 *
 * <p>{@link #advanceOneTick(Map)} 만이 상태를 움직인다. 이 함수가 순수하기 때문에 기보 재생이
 * 원본과 같은 결과를 낸다는 것을 테스트로 증명할 수 있다.
 *
 * <p>v3 규칙: 격자는 36×48 서브칸, 플레이어는 {@link DodgeRules#PLAYER_SIZE}×동일 박스(좌표는
 * 왼쪽 위), 이동은 입력 1회에 {@link DodgeRules#MOVE_STEP} 서브칸, 장애물은 가변 크기
 * {@link Obstacle} 이고 낙하 속도는 {@link DodgeRules#fallSpeed(int)} 가 틱에 따라 올린다.
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

    private List<Obstacle> obstacles;
    private int tick;
    private boolean finished;

    /** 실제 게임 생성자. 시작 위치는 {@link DodgeRules#startingCells(int)} 가 정하고, 장애물은 없이 시작한다. */
    public DodgeGame(List<String> participantIds, int seed) {
        this(participantIds, seed, defaultStartingPositions(participantIds), List.of(), null, 0);
    }

    /**
     * 시나리오 생성자. 시작 위치·장애물·스폰 확률·시작 틱을 직접 지정한다.
     *
     * <p>테스트가 이동/충돌/순위 로직을 각각 격리해서 검증할 때 쓴다. {@code spawnProbabilityOverride}
     * 에 0.0 을 넘기면 장애물이 절대 새로 생기지 않는 "조용한" 게임이 된다. {@code startingTick} 은
     * 낙하 속도({@link DodgeRules#fallSpeed(int)})가 올라간 구간을 300틱을 돌리지 않고 검증하기
     * 위한 이음매다 — 프로덕션 생성자는 언제나 0 이다.
     */
    DodgeGame(List<String> participantIds,
              int seed,
              Map<String, Cell> startingPositions,
              List<Obstacle> startingObstacles,
              Double spawnProbabilityOverride,
              int startingTick) {
        this.participantIds = List.copyOf(participantIds);
        this.seed = seed;
        this.random = new Xorshift32(seed);
        this.positions = new LinkedHashMap<>(startingPositions);
        this.obstacles = new ArrayList<>(startingObstacles);
        this.spawnProbabilityOverride = spawnProbabilityOverride;
        this.tick = startingTick;
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

        Map<String, Cell> previous = new LinkedHashMap<>(positions);

        applyInputs(inputs);

        int fall = DodgeRules.fallSpeed(tick);
        List<Obstacle> fallen = new ArrayList<>(obstacles.size());
        for (Obstacle obstacle : obstacles) {
            int y = obstacle.y() + fall;
            if (y < DodgeRules.ROWS) {
                fallen.add(new Obstacle(obstacle.x(), y, obstacle.w(), obstacle.h()));
            }
        }

        double probability = spawnProbabilityOverride != null
                ? spawnProbabilityOverride
                : DodgeRules.spawnProbability(tick);
        // 슬롯 0부터 오름차순으로 굴린다 — 굴림 순서와 굴림 수가 난수열의 일부라 브라우저
        // 기보 재생이 같은 결과를 내려면 순서도, 크기 샘플링의 굴림 수(폭 1회 + 높이 1회)도
        // 바꾸면 안 된다.
        for (int slot = 0; slot < DodgeRules.SPAWN_SLOTS; slot++) {
            if (random.nextDouble() < probability) {
                int w = DodgeRules.MIN_OBSTACLE_WIDTH + (int) Math.floor(
                        random.nextDouble() * (DodgeRules.MAX_OBSTACLE_WIDTH - DodgeRules.MIN_OBSTACLE_WIDTH + 1));
                int h = DodgeRules.MIN_OBSTACLE_HEIGHT + (int) Math.floor(
                        random.nextDouble() * (DodgeRules.MAX_OBSTACLE_HEIGHT - DodgeRules.MIN_OBSTACLE_HEIGHT + 1));
                int x = Math.min(slot * DodgeRules.SUBCELLS_PER_CELL, DodgeRules.COLUMNS - w);
                fallen.add(new Obstacle(x, 0, w, h));
            }
        }

        obstacles = fallen;

        List<String> eliminated = detectCollisions(previous, fall);
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
     * 충돌 판정. 두 갈래다: (1) 틱이 끝난 시점의 박스 겹침(AABB), (2) 서로 지나친 경우(스왑) —
     * 참가자의 현재 박스가 장애물의 직전 박스(이번 틱의 낙하량만큼 위)와 겹치고 참가자의 직전
     * 박스가 장애물의 현재 박스와 겹치면, 둘은 한 틱 안에서 서로를 뚫고 지나간 것이다.
     *
     * <p>스왑 검사가 필요한 것은 낙하가 2서브칸 이상인 구간뿐이다(1서브칸일 때는 상대 변위가
     * 두 몸높이 합보다 항상 작아 겹침 없는 통과가 불가능하다). 수평 이동은 이동량
     * ({@link DodgeRules#MOVE_STEP}=3)이 플레이어 한 변과 같아 직전·현재 박스가 빈틈없이
     * 이어지므로 끝점 검사로 충분하고, 가만히 서 있는 플레이어를 블록이 통째로 건너뛰는 일은
     * {@link DodgeRules#MAX_FALL_SPEED} &lt; PLAYER_SIZE + MIN_OBSTACLE_HEIGHT 가 막는다 —
     * 이 부등식들은 {@code DodgeRulesTest} 가 상수 차원에서 고정한다.
     */
    private List<String> detectCollisions(Map<String, Cell> previousPositions, int fall) {
        List<String> eliminated = new ArrayList<>();

        for (Map.Entry<String, Cell> entry : positions.entrySet()) {
            Cell now = entry.getValue();
            Cell before = previousPositions.get(entry.getKey());

            boolean hit = obstacles.stream().anyMatch(obstacle -> {
                if (overlapsPlayerBoxAt(obstacle, now)) {
                    return true;
                }
                if (before == null) {
                    return false;
                }
                Obstacle obstacleBefore =
                        new Obstacle(obstacle.x(), obstacle.y() - fall, obstacle.w(), obstacle.h());
                return overlapsPlayerBoxAt(obstacleBefore, now) && overlapsPlayerBoxAt(obstacle, before);
            });
            if (hit) {
                eliminated.add(entry.getKey());
            }
        }

        return eliminated;
    }

    private boolean overlapsPlayerBoxAt(Obstacle obstacle, Cell topLeft) {
        return obstacle.overlaps(
                topLeft.x(),
                topLeft.y(),
                topLeft.x() + DodgeRules.PLAYER_SIZE - 1,
                topLeft.y() + DodgeRules.PLAYER_SIZE - 1
        );
    }

    private void applyInputs(Map<String, Direction> inputs) {
        for (Map.Entry<String, Direction> entry : inputs.entrySet()) {
            Cell current = positions.get(entry.getKey());
            Direction direction = entry.getValue();
            if (current == null || direction == null) {
                continue;
            }

            int x = current.x() + direction.dx() * DodgeRules.MOVE_STEP;
            int y = current.y() + direction.dy() * DodgeRules.MOVE_STEP;
            if (x < 0 || x > DodgeRules.COLUMNS - DodgeRules.PLAYER_SIZE
                    || y < 0 || y > DodgeRules.ROWS - DodgeRules.PLAYER_SIZE) {
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

    /**
     * 틱을 진행하지 않고 지금 이 순간의 프레임을 읽는다. 재접속한 참가자에게 화면을 다시 그려 주려면
     * 판을 한 칸도 움직이지 않은 채로 현재 상태가 필요하다 — {@link #advanceOneTick(Map)} 을 한 번
     * 더 부르는 것은 그 참가자 때문에 게임이 앞으로 가는 것이라 답이 될 수 없다.
     *
     * <p>{@code eliminatedThisTick} 은 빈 목록이다. 이 호출은 아무도 탈락시키지 않는다.
     */
    public DodgeFrame currentFrame() {
        return frame(List.of());
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
