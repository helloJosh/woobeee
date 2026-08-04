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

    /** GAME-AC-16 — 입력 1회는 {@link DodgeRules#MOVE_STEP} 서브칸(=옛 1칸)이다. */
    @Test
    void oneInputPerParticipantPerTick() {
        DodgeGame game = quietGame(A);
        Cell start = game.advanceOneTick(Map.of()).positions().get(A);

        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.LEFT));

        assertThat(frame.positions().get(A).x()).isEqualTo(start.x() - DodgeRules.MOVE_STEP);
        assertThat(frame.positions().get(A).y()).isEqualTo(start.y());
    }

    /** GAME-AC-16 — 플레이어 박스(3×3)가 격자를 벗어나는 이동은 무시한다. */
    @Test
    void movesOffTheGridAreIgnored() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1, Map.of(A, new Cell(0, DodgeRules.ROWS - DodgeRules.PLAYER_SIZE)), List.of(), A);

        DodgeFrame left = game.advanceOneTick(Map.of(A, Direction.LEFT));
        assertThat(left.positions().get(A).x()).isZero();

        DodgeFrame down = game.advanceOneTick(Map.of(A, Direction.DOWN));
        assertThat(down.positions().get(A).y()).isEqualTo(DodgeRules.ROWS - DodgeRules.PLAYER_SIZE);
    }

    /**
     * GAME-AC-23: the reconnect snapshot needs the frame as it stands right now. Calling
     * advanceOneTick again would answer with a frame the rest of the room never saw and move the
     * game forward for everyone because one player came back.
     */
    @Test
    void currentFrameReportsTheStateWithoutAdvancingTheTick() {
        DodgeGame game = DodgeGameTestSupport.quiet(1, List.of(new Obstacle(6, 0, 2, 2)), A);
        DodgeFrame advanced = game.advanceOneTick(Map.of(A, Direction.LEFT));

        DodgeFrame snapshot = game.currentFrame();

        assertThat(game.tick()).isEqualTo(1);
        assertThat(snapshot.tick()).isEqualTo(advanced.tick());
        assertThat(snapshot.positions()).isEqualTo(advanced.positions());
        assertThat(snapshot.obstacles()).isEqualTo(advanced.obstacles());
        assertThat(snapshot.eliminatedThisTick()).isEmpty();
    }

    @Test
    void obstaclesFallOneSubcellPerTick() {
        DodgeGame game = DodgeGameTestSupport.quiet(1, List.of(new Obstacle(6, 0, 4, 2)), A);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).containsExactly(new Obstacle(6, 1, 4, 2));
    }

    @Test
    void obstaclesLeavingTheBottomAreRemoved() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(0, DodgeRules.ROWS - DodgeRules.PLAYER_SIZE)),
                List.of(new Obstacle(9, DodgeRules.ROWS - 1, 2, 2)),
                A);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).isEmpty();
    }

    @Test
    void anObstacleOverlappingThePlayerBoxEliminatesThePlayer() {
        // A 의 박스는 (15..17, 30..32). 장애물 (15,28,2,2)는 이 틱에 (15,29,2,2)로 내려와
        // 아랫줄(30)이 A 의 윗줄과 겹친다.
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(15, 30), B, new Cell(0, 45)),
                List.of(new Obstacle(15, 28, 2, 2)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.eliminatedThisTick()).containsExactly(A);
        assertThat(game.survivors()).containsExactly(B);
    }

    /**
     * GAME-AC-17 — 위로 이동해 낙하 블록을 통과하려는 시도도 충돌이다. v2 는 별도의 스왑
     * 검사가 필요했지만, v3 은 이동량(3)이 플레이어 박스 한 변(3)과 같아 직전·현재 박스가
     * 빈틈없이 이어지므로 "겹침 없이 지나치는" 배치 자체가 존재하지 않는다 — 끝점 겹침만으로
     * 같은 행동이 잡히는지를 이 테스트가 고정한다.
     */
    @Test
    void movingUpThroughAFallingObstacleIsACollision() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(15, 30), B, new Cell(0, 45)),
                List.of(new Obstacle(15, 26, 2, 2)),
                A, B);

        // A 가 위로 3서브칸 올라가고(30→27) 장애물이 한 칸 내려오면(26..27→27..28)
        // A 의 새 박스(27..29)와 겹친다.
        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.UP));

        assertThat(frame.eliminatedThisTick()).containsExactly(A);
    }

    @Test
    void movingAsideAvoidsTheObstacle() {
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(15, 30), B, new Cell(0, 45)),
                List.of(new Obstacle(15, 28, 3, 2)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of(A, Direction.LEFT));

        assertThat(frame.eliminatedThisTick()).isEmpty();
        assertThat(frame.positions().get(A)).isEqualTo(new Cell(12, 30));
    }

    /** GAME-AC-18 */
    @Test
    void rankIsTheReverseOfEliminationOrder() {
        // B 의 장애물은 한 줄 위(27)에서 시작해 다음 틱에야 B 의 박스에 닿는다 —
        // 두 번째 주입 없이 초기 배치만으로 탈락 순서를 재현한다.
        DodgeGame game = DodgeGameTestSupport.quiet(
                1,
                Map.of(A, new Cell(3, 30), B, new Cell(15, 30), "g:c", new Cell(27, 30)),
                List.of(new Obstacle(3, 28, 2, 2), new Obstacle(15, 27, 2, 2)),
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
                Map.of(A, new Cell(3, 30), B, new Cell(15, 30), "g:c", new Cell(27, 30)),
                List.of(new Obstacle(3, 28, 2, 2), new Obstacle(15, 28, 2, 2)),
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
                Map.of(A, new Cell(3, 30), B, new Cell(27, 30)),
                List.of(new Obstacle(3, 28, 2, 2)),
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
                Map.of(A, new Cell(15, 30)),
                List.of(new Obstacle(15, 28, 2, 2)),
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
                Map.of(A, new Cell(3, 30), B, new Cell(15, 30)),
                List.of(new Obstacle(3, 28, 2, 2), new Obstacle(15, 28, 2, 2)),
                A, B);

        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.finished()).isTrue();
        assertThat(game.finalRanks()).containsEntry(A, 1);
        assertThat(game.finalRanks()).containsEntry(B, 1);
    }

    /**
     * F2 — 골든 값 검증. seed=12345 의 xorshift32 시퀀스를 <b>독립 구현으로</b> 굴려 슬롯별
     * nextDouble() 을 스폰 확률(tick 0..1 은 0.05)과 비교해 미리 얻은 기대값이다.
     *
     * <pre>
     * tick 0 (1~12번째 nextDouble): 최솟값이 0.114765 (슬롯 11) — 전부 0.05 이상이라 스폰 없음.
     * tick 1 (13번째부터):
     *   슬롯 0: 0.300349          — 스폰 없음
     *   슬롯 1: 0.034989 &lt; 0.05 — 스폰. 폭 굴림 0.668699 → w = 2+floor(0.668699*4) = 4,
     *                              높이 굴림 0.301702 → h = 2+floor(0.301702*2) = 2, x = 1*3 = 3
     *   슬롯 2: 0.025221 &lt; 0.05 — 스폰. 폭 굴림 0.754853 → w = 5, 높이 굴림 0.120995 → h = 2, x = 6
     *   슬롯 3..11: 0.800961, 0.087412, 0.651159, 0.434973, 0.384712, ... 전부 0.05 이상 — 스폰 없음
     * </pre>
     *
     * <p>이 값은 DodgeGame 을 실행해 나온 출력을 그대로 베낀 것이 아니라, Xorshift32 의
     * 알고리즘({@code x^=x<<13; x^=x>>>17; x^=x<<5;})과 크기 샘플링 식을 JS 로 별도 재현해서
     * 구했다 — 그래야 슬롯 오름차순 규칙과 "히트당 폭 1회·높이 1회" 굴림 수가 실제로 고정돼
     * 있는지를 검증하는 테스트가 된다. 굴림 수가 하나라도 달라지면 이후 난수열 전체가 밀려
     * 즉시 깨진다.
     */
    @Test
    void spawnsMatchTheIndependentlyDerivedXorshiftSequence() {
        DodgeGame game = new DodgeGame(List.of(A, B), 12345);

        DodgeFrame tick0 = game.advanceOneTick(Map.of());
        DodgeFrame tick1 = game.advanceOneTick(Map.of());

        assertThat(tick0.obstacles()).isEmpty();
        assertThat(tick1.obstacles()).containsExactly(
                new Obstacle(3, 0, 4, 2),
                new Obstacle(6, 0, 5, 2));
    }

    /**
     * M2 — 골든 값. 리스트의 <i>순서</i>도 브라우저 계약이다: 클라이언트가 obstacles 를 배열
     * 위치로 다루거나(렌더 순서), 낙하분과 신규 생성분을 다른 순서로 이어 붙이면 재생 화면이
     * 원본과 달라진다. 그래서 낙하분과 신규 생성분이 함께 있는 틱 하나를 통째로 고정한다.
     *
     * <p>같은 독립 재현으로: tick 2·3 은 스폰이 없고, tick 4 는 슬롯 11 에서
     * 0.030743 &lt; 0.05 로 스폰된다 — 폭 굴림 0.166594 → w = 2, 높이 굴림 0.260201 → h = 2,
     * x = min(33, 36-2) = 33. tick 1 의 두 블록은 세 틱 내려와 y = 3 이다.
     */
    @Test
    void aMultiObstacleTickPinsFallenBeforeSpawnedAndAscendingSlots() {
        DodgeGame game = new DodgeGame(List.of(A, B), 12345);

        for (int i = 0; i < 4; i++) {
            game.advanceOneTick(Map.of());
        }
        DodgeFrame frame = game.advanceOneTick(Map.of());

        assertThat(frame.obstacles()).containsExactly(
                new Obstacle(3, 3, 4, 2),
                new Obstacle(6, 3, 5, 2),
                new Obstacle(33, 0, 2, 2));
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
                Map.of(A, new Cell(3, 30), B, new Cell(27, 30)),
                List.of(new Obstacle(3, 28, 2, 2)),
                A, B);
        game.advanceOneTick(Map.of());

        int tickAfterFinish = game.tick();
        game.advanceOneTick(Map.of());

        assertThat(game.tick()).isEqualTo(tickAfterFinish);
    }

    @Test
    void eliminatingADepartedPlayerAdvancesTheEliminationOrder() {
        DodgeGame game = DodgeGameTestSupport.quiet(1, A, B);

        game.eliminate(A);

        assertThat(game.survivors()).containsExactly(B);
        assertThat(game.finished()).isTrue();
        assertThat(game.finalRanks()).containsEntry(B, 1).containsEntry(A, 2);
    }

    @Test
    void eliminatingAnUnknownParticipantIsANoop() {
        DodgeGame game = DodgeGameTestSupport.quiet(1, A, B, "g:c");

        game.eliminate("g:zzz");

        assertThat(game.survivors()).hasSize(3);
    }
}
