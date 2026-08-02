package com.woobeee.game.dodge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DodgeGameTest {

    private static final String A = "m:11";
    private static final String B = "g:a";

    /** 장애물이 절대 생성되지 않는 게임 — 이동 규칙만 따로 본다. */
    private DodgeGame quietGame(String... players) {
        return DodgeGameTestSupport.quiet(1, players);
    }

    /** GAME-AC-16 */
    @Test
    void oneInputPerParticipantPerTick() {
        DodgeGame game = quietGame(A);
        Cell start = game.advanceOneTick(Map.of()).positions().get(A);

        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.LEFT));

        assertThat(frame.positions().get(A).x()).isEqualTo(start.x() - 1);
        assertThat(frame.positions().get(A).y()).isEqualTo(start.y());
    }

    /** GAME-AC-16 */
    @Test
    void movesOffTheGridAreIgnored() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1, Map.of(A, new Cell(0, DodgeRules.ROWS - 1)), List.of(), A);

        DodgeFrame left = game.advanceOneTick(Map.of(A, Direction.LEFT));
        assertThat(left.positions().get(A).x()).isZero();

        DodgeFrame down = game.advanceOneTick(Map.of(A, Direction.DOWN));
        assertThat(down.positions().get(A).y()).isEqualTo(DodgeRules.ROWS - 1);
    }

    @Test
    void obstaclesFallOneRowPerTick() {
        DodgeGame game = DodgeGameTestSupport.quiet(1, List.of(new Cell(3, 0)), A);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).containsExactly(new Cell(3, 1));
    }

    @Test
    void obstaclesLeavingTheBottomAreRemoved() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(0, DodgeRules.ROWS - 1)),
                List.of(new Cell(3, DodgeRules.ROWS - 1)),
                A);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).isEmpty();
    }

    @Test
    void sharingACellWithAnObstacleEliminatesThePlayer() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(5, 10), B, new Cell(0, 15)),
                List.of(new Cell(5, 9)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.eliminatedThisTick()).containsExactly(A);
        assertThat(game.survivors()).containsExactly(B);
    }

    /** GAME-AC-17 — 스왑도 충돌이다. 이걸 빠뜨리면 위로 통과하는 버그가 된다. */
    @Test
    void swappingThroughAFallingObstacleIsACollision() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(5, 10), B, new Cell(0, 15)),
                List.of(new Cell(5, 9)),
                A, B);

        // A 가 위로 올라가고 장애물이 내려오면 격자상 겹치지 않고 서로 지나친다.
        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.UP));

        assertThat(frame.eliminatedThisTick()).containsExactly(A);
    }

    @Test
    void movingAsideAvoidsTheObstacle() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(5, 10), B, new Cell(0, 15)),
                List.of(new Cell(5, 9)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.LEFT));

        assertThat(frame.eliminatedThisTick()).isEmpty();
        assertThat(frame.positions().get(A)).isEqualTo(new Cell(4, 10));
    }

    /** GAME-AC-18 */
    @Test
    void rankIsTheReverseOfEliminationOrder() {
        // B 의 장애물은 한 칸 위(row 8)에서 시작해 다음 틱에야 row 10 에 도달한다 —
        // 두 번째 forceObstaclesForTest 호출 대신, 초기 배치만으로 같은 타이밍을 재현한다.
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(1, 10), B, new Cell(5, 10), "g:c", new Cell(9, 10)),
                List.of(new Cell(1, 9), new Cell(5, 8)),
                A, B, "g:c");

        game.advanceOneTick(Map.of());
        DodgeFrame last = game.advanceOneTick(Map.of());

        assertThat(last.finished()).isTrue();
        assertThat(game.finalRanks()).containsEntry("g:c", 1);
        assertThat(game.finalRanks()).containsEntry(B, 2);
        assertThat(game.finalRanks()).containsEntry(A, 3);
    }

    /** GAME-AC-18 */
    @Test
    void sameTickEliminationsShareARank() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(1, 10), B, new Cell(5, 10), "g:c", new Cell(9, 10)),
                List.of(new Cell(1, 9), new Cell(5, 9)),
                A, B, "g:c");

        game.advanceOneTick(Map.of());

        assertThat(game.finalRanks()).containsEntry(A, 2);
        assertThat(game.finalRanks()).containsEntry(B, 2);
        assertThat(game.finalRanks()).containsEntry("g:c", 1);
    }

    @Test
    void theGameEndsWhenOneSurvivorRemains() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(1, 10), B, new Cell(9, 10)),
                List.of(new Cell(1, 9)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.finished()).isTrue();
        assertThat(game.finished()).isTrue();
    }

    /** F1 — 참가자가 한 명뿐이어도 그 한 명이 맞으면 게임은 끝나야 한다. */
    @Test
    void aSoloGameEndsWhenItsOnlyParticipantIsHit() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(5, 10)),
                List.of(new Cell(5, 9)),
                A);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.finished()).isTrue();
        assertThat(game.finished()).isTrue();
        assertThat(game.finalRanks()).containsEntry(A, 1);
    }

    @Test
    void everyoneDyingOnTheSameTickEndsTheGameWithAJointFirst() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(1, 10), B, new Cell(5, 10)),
                List.of(new Cell(1, 9), new Cell(5, 9)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.finished()).isTrue();
        assertThat(game.finalRanks()).containsEntry(A, 1);
        assertThat(game.finalRanks()).containsEntry(B, 1);
    }

    /**
     * F2 — 골든 값 검증. seed=12345, tick 0 의 xorshift32 시퀀스를 손으로 굴려 컬럼별
     * nextDouble() 을 스폰 확률(0.15, tick 0)과 비교해서 미리 얻은 기대값이다.
     *
     * <pre>
     * state = 12345 (seed != 0 이라 그대로 씀)
     * 컬럼 0..11 의 nextDouble() 값 (Xorshift32(12345) 를 12번 호출) —
     *   0: 0.776939   1: 0.395173   2: 0.655770   3: 0.455296
     *   4: 0.167369   5: 0.764527   6: 0.997839   7: 0.857029
     *   8: 0.549425   9: 0.509477  10: 0.175607  11: 0.114765
     * spawnProbability(0) == DodgeRules.BASE_SPAWN == 0.15 이므로
     * nextDouble() &lt; 0.15 인 컬럼은 11 하나뿐이다 (0.114765 &lt; 0.15).
     * 나머지 11개 컬럼은 전부 0.15 이상이라 스폰되지 않는다.
     * 따라서 tick 0 의 유일한 장애물은 (11, 0) 이다.
     * </pre>
     *
     * <p>이 값은 DodgeGame 을 실행해 나온 출력을 그대로 베낀 것이 아니라, Xorshift32 의
     * 알고리즘(각 스텝에서 {@code x^=x<<13; x^=x>>>17; x^=x<<5;})을 별도로 재현해서 구했다 —
     * 그래야 컬럼 오름차순 규칙이 실제로 고정돼 있는지를 검증하는 테스트가 된다. 컬럼을
     * 내림차순으로 굴리거나 스폰을 아예 지워도 통과했던 이전의 상호 비교 테스트와 달리,
     * 이 값은 순서가 틀리면 즉시 깨진다.
     */
    @Test
    void spawnColumnsMatchTheHandDerivedXorshiftSequence() {
        DodgeGame game = new DodgeGame(List.of(A, B), 12345);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).containsExactly(new Cell(11, 0));
    }

    /** 같은 시드는 같은 결과를 낸다 — 골든 테스트를 보강하는 회귀 방지용 상호 비교다. */
    @Test
    void spawningIsRepeatableAcrossInstancesWithTheSameSeed() {
        DodgeGame first = new DodgeGame(List.of(A, B), 12345);
        DodgeGame second = new DodgeGame(List.of(A, B), 12345);

        for (int i = 0; i < 30; i++) {
            DodgeFrame a = first.advanceOneTick(Map.of());
            DodgeFrame b = second.advanceOneTick(Map.of());
            assertThat(a.obstacles()).isEqualTo(b.obstacles());
        }
    }

    @Test
    void aFinishedGameIgnoresFurtherTicks() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(1, 10), B, new Cell(9, 10)),
                List.of(new Cell(1, 9)),
                A, B);
        game.advanceOneTick(Map.of());

        int tickAfterFinish = game.tick();
        game.advanceOneTick(Map.of());

        assertThat(game.tick()).isEqualTo(tickAfterFinish);
    }
}
