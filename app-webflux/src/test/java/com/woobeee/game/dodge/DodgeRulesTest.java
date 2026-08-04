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
     * v3 충돌 판정이 끝점 겹침만으로 완결되는 전제(DodgeGame.detectCollisions 주석)를
     * 상수 차원에서 고정한다: 이동량 = 플레이어 한 변, 그리고 상대 변위(이동량 + 낙하 1)가
     * 두 몸높이의 합(플레이어 한 변 + 최소 장애물 높이)보다 작아야 한다. 이 부등식이 깨지는
     * 상수 변경은 스윕/스왑 검사를 되살려야 하는 변경이다.
     */
    @Test
    void constantsKeepTunnellingImpossible() {
        assertThat(DodgeRules.MOVE_STEP).isEqualTo(DodgeRules.PLAYER_SIZE);
        assertThat(DodgeRules.MOVE_STEP + 1)
                .isLessThan(DodgeRules.PLAYER_SIZE + DodgeRules.MIN_OBSTACLE_HEIGHT);
    }

    @Test
    void spawnProbabilityStartsAtFivePercent() {
        assertThat(DodgeRules.spawnProbability(0)).isCloseTo(0.05, within(1e-9));
        assertThat(DodgeRules.spawnProbability(99)).isCloseTo(0.05, within(1e-9));
    }

    @Test
    void spawnProbabilityRisesTwoPointsEveryTenSeconds() {
        assertThat(DodgeRules.spawnProbability(100)).isCloseTo(0.07, within(1e-9));
        assertThat(DodgeRules.spawnProbability(200)).isCloseTo(0.09, within(1e-9));
    }

    @Test
    void spawnProbabilityCapsAtTwentyPercent() {
        assertThat(DodgeRules.spawnProbability(100_000)).isCloseTo(0.20, within(1e-9));
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
