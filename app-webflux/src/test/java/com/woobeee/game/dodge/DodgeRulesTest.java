package com.woobeee.game.dodge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DodgeRulesTest {

    @Test
    void gridIsTwelveBySixteen() {
        assertThat(DodgeRules.COLUMNS).isEqualTo(12);
        assertThat(DodgeRules.ROWS).isEqualTo(16);
        assertThat(DodgeRules.TICK_MILLIS).isEqualTo(100);
    }

    @Test
    void spawnProbabilityStartsAtFifteenPercent() {
        assertThat(DodgeRules.spawnProbability(0)).isCloseTo(0.15, within(1e-9));
        assertThat(DodgeRules.spawnProbability(99)).isCloseTo(0.15, within(1e-9));
    }

    @Test
    void spawnProbabilityRisesFivePointsEveryTenSeconds() {
        assertThat(DodgeRules.spawnProbability(100)).isCloseTo(0.20, within(1e-9));
        assertThat(DodgeRules.spawnProbability(200)).isCloseTo(0.25, within(1e-9));
    }

    @Test
    void spawnProbabilityCapsAtSixtyPercent() {
        assertThat(DodgeRules.spawnProbability(100_000)).isCloseTo(0.60, within(1e-9));
    }

    @Test
    void startingCellsSitOnTheBottomRowAndAreSpreadOut() {
        List<Cell> cells = DodgeRules.startingCells(4);

        assertThat(cells).hasSize(4);
        assertThat(cells).allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - 1));
        assertThat(cells.stream().map(Cell::x).distinct()).hasSize(4);
        assertThat(cells.stream().map(Cell::x)).allSatisfy(
                x -> assertThat(x).isBetween(0, DodgeRules.COLUMNS - 1));
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
     * 원본과 달라진다. 그런데 "서로 다르고 격자 안"이라는 기존 검증은 그 계약을 전혀 고정하지
     * 못한다 — {@code floor(i * COLUMNS / playerCount)} 같은 완전히 다른 공식도 그 조건은
     * 만족하기 때문이다(실제로 Plan 4 의 TS 포트가 그 공식으로 쓰여 있었다).
     *
     * <p>기대값은 {@code round((i + 0.5) * 12 / 8 - 0.5) = round(1.5i + 0.25)} 를 손으로 굴려
     * 얻었다: 0.25→0, 1.75→2, 3.25→3, 4.75→5, 6.25→6, 7.75→8, 9.25→9, 10.75→11.
     * Java 의 {@code Math.round} 와 JS 의 {@code Math.round} 는 둘 다 0.5 를 +∞ 쪽으로
     * 올림하므로 이 식은 그대로 포팅된다.
     */
    @Test
    void startingCellsForEightPlayersAreExactlyTheseColumns() {
        List<Cell> cells = DodgeRules.startingCells(8);

        assertThat(cells.stream().map(Cell::x)).containsExactly(0, 2, 3, 5, 6, 8, 9, 11);
        assertThat(cells).allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - 1));
    }

    /**
     * C1 — 경계 인원수의 골든. 1인은 중앙에서 오른쪽으로 반 칸(6), 2인은 4분위(3, 9)다.
     * {@code floor} 기반 공식이면 각각 [0] 과 [0, 6] 이 나오므로 여기서 즉시 깨진다.
     */
    @Test
    void startingCellsForOneAndTwoPlayersAreExactlyTheseColumns() {
        assertThat(DodgeRules.startingCells(1).stream().map(Cell::x)).containsExactly(6);
        assertThat(DodgeRules.startingCells(2).stream().map(Cell::x)).containsExactly(3, 9);
        assertThat(DodgeRules.startingCells(1))
                .allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - 1));
        assertThat(DodgeRules.startingCells(2))
                .allSatisfy(cell -> assertThat(cell.y()).isEqualTo(DodgeRules.ROWS - 1));
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
