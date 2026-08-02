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
