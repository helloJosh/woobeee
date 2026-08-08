package com.woobeee.game.dodge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DodgeRulesTest {

    @Test
    void gridIsThirtySixByFortyEightSubcells() {
        assertThat(DodgeRules.COLUMNS).isEqualTo(36);
        assertThat(DodgeRules.ROWS).isEqualTo(48);
        assertThat(DodgeRules.TICK_MILLIS).isEqualTo(100);
        assertThat(DodgeRules.SPAWN_SLOTS).isEqualTo(12);
        assertThat(DodgeRules.PLAYER_SIZE).isEqualTo(3);
        assertThat(DodgeRules.MOVE_STEP).isEqualTo(3);
    }

    /**
     * 충돌 판정(끝점 AABB + 상호 통과 검사, DodgeGame.detectCollisions 주석)이 완결되기 위한
     * 상수 전제 둘을 고정한다: (1) 이동량 = 플레이어 한 변 — 수평 이동의 직전·현재 박스가
     * 빈틈없이 이어져 끝점 검사로 충분하다. (2) 최대 낙하 속도 < 플레이어 한 변 + 최소 장애물
     * 높이 — 가만히 서 있는 플레이어를 블록이 한 틱에 통째로 건너뛰지 못한다(움직이는
     * 플레이어와의 교차 통과는 상호 통과 검사가 잡는다). 이 부등식이 깨지는 상수 변경은
     * 충돌 판정을 다시 설계해야 하는 변경이다.
     */
    @Test
    void constantsKeepTunnellingImpossible() {
        assertThat(DodgeRules.MOVE_STEP).isEqualTo(DodgeRules.PLAYER_SIZE);
        assertThat(DodgeRules.MAX_FALL_SPEED)
                .isLessThan(DodgeRules.PLAYER_SIZE + DodgeRules.MIN_OBSTACLE_HEIGHT);
    }

    @Test
    void spawnProbabilityStartsAtOnePercent() {
        assertThat(DodgeRules.spawnProbability(0)).isCloseTo(0.01, within(1e-9));
        assertThat(DodgeRules.spawnProbability(99)).isCloseTo(0.01, within(1e-9));
    }

    @Test
    void spawnProbabilityRisesOnePointEveryTenSeconds() {
        assertThat(DodgeRules.spawnProbability(100)).isCloseTo(0.02, within(1e-9));
        assertThat(DodgeRules.spawnProbability(200)).isCloseTo(0.03, within(1e-9));
    }

    @Test
    void spawnProbabilityCapsAtFifteenPercent() {
        assertThat(DodgeRules.spawnProbability(100_000)).isCloseTo(0.15, within(1e-9));
    }

    /** 낙하 속도는 30초(300틱)마다 1씩 올라 3에서 멈춘다 — 후반의 압박은 밀도와 속도 둘 다다. */
    @Test
    void fallSpeedRampsEveryThirtySecondsAndCapsAtThree() {
        assertThat(DodgeRules.fallSpeed(0)).isEqualTo(1);
        assertThat(DodgeRules.fallSpeed(299)).isEqualTo(1);
        assertThat(DodgeRules.fallSpeed(300)).isEqualTo(2);
        assertThat(DodgeRules.fallSpeed(599)).isEqualTo(2);
        assertThat(DodgeRules.fallSpeed(600)).isEqualTo(3);
        assertThat(DodgeRules.fallSpeed(100_000)).isEqualTo(3);
    }

    @Test
    void obstacleSizesAreBoundedSubcellBoxes() {
        assertThat(DodgeRules.MIN_OBSTACLE_WIDTH).isEqualTo(2);
        assertThat(DodgeRules.MAX_OBSTACLE_WIDTH).isEqualTo(5);
        assertThat(DodgeRules.MIN_OBSTACLE_HEIGHT).isEqualTo(2);
        assertThat(DodgeRules.MAX_OBSTACLE_HEIGHT).isEqualTo(3);
    }

    @Test
    void startingCellsSitOnTheBottomAndAreSpreadOut() {
        List<Cell> cells = DodgeRules.startingCells(4);

        assertThat(cells).hasSize(4);
        assertThat(cells).allSatisfy(cell ->
                assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - DodgeRules.PLAYER_SIZE));
        assertThat(cells.stream().map(Cell::x).distinct()).hasSize(4);
        assertThat(cells.stream().map(Cell::x)).allSatisfy(
                x -> assertThat(x).isBetween(0, DodgeRules.COLUMNS - DodgeRules.PLAYER_SIZE));
    }

    @Test
    void startingCellsHandleTheFullEightPlayers() {
        List<Cell> cells = DodgeRules.startingCells(8);

        assertThat(cells).hasSize(8);
        assertThat(cells.stream().map(Cell::x).distinct()).hasSize(8);
    }

    /**
     * C1 — 골든 값. 시작 칸의 정확한 배치는 브라우저 재생기와의 <b>교차 언어 계약</b>이다:
     * 클라이언트가 한 칸이라도 다르게 계산하면 틱 1부터 위치가 갈리고, 그 뒤 충돌 판정 전체가
     * 원본과 달라진다.
     *
     * <p>기대값은 옛 12칸 공식 {@code round((i + 0.5) * 12 / 8 - 0.5) = round(1.5i + 0.25)} 를
     * 손으로 굴려 얻은 슬롯 [0, 2, 3, 5, 6, 8, 9, 11] 에 ×3 한 것이다. Java 의
     * {@code Math.round} 와 JS 의 {@code Math.round} 는 둘 다 0.5 를 +∞ 쪽으로 올림하므로
     * 이 식은 그대로 포팅된다.
     */
    @Test
    void startingCellsForEightPlayersAreExactlyTheseSubcells() {
        List<Cell> cells = DodgeRules.startingCells(8);

        assertThat(cells.stream().map(Cell::x)).containsExactly(0, 6, 9, 15, 18, 24, 27, 33);
        assertThat(cells).allSatisfy(cell ->
                assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - DodgeRules.PLAYER_SIZE));
    }

    /**
     * C1 — 경계 인원수의 골든. 1인은 슬롯 6(서브칸 18), 2인은 슬롯 3·9(서브칸 9·27)다.
     * {@code floor} 기반 공식이면 각각 [0] 과 [0, 18] 이 나오므로 여기서 즉시 깨진다.
     */
    @Test
    void startingCellsForOneAndTwoPlayersAreExactlyTheseSubcells() {
        assertThat(DodgeRules.startingCells(1).stream().map(Cell::x)).containsExactly(18);
        assertThat(DodgeRules.startingCells(2).stream().map(Cell::x)).containsExactly(9, 27);
        assertThat(DodgeRules.startingCells(1))
                .allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - DodgeRules.PLAYER_SIZE));
        assertThat(DodgeRules.startingCells(2))
                .allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - DodgeRules.PLAYER_SIZE));
    }

    @Test
    void directionsMapToGridDeltas() {
        assertThat(Direction.UP.dy()).isEqualTo(-1);
        assertThat(Direction.DOWN.dy()).isEqualTo(1);
        assertThat(Direction.LEFT.dx()).isEqualTo(-1);
        assertThat(Direction.RIGHT.dx()).isEqualTo(1);
    }

    @Test
    void directionParseIsForgivingAboutCaseAndNull() {
        assertThat(Direction.parse("left")).isEqualTo(Direction.LEFT);
        assertThat(Direction.parse("UP")).isEqualTo(Direction.UP);
        assertThat(Direction.parse("sideways")).isNull();
        assertThat(Direction.parse(null)).isNull();
    }
}
