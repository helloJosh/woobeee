package com.woobeee.game.omok;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LineScannerTest {

    private static final String EMPTY_ROW = "...............";

    private OmokBoard boardWithRow(int rowIndex, String row) {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = i == rowIndex ? row : EMPTY_ROW;
        }
        return OmokBoard.of(rows);
    }

    private OmokBoard boardWithColumn(int colIndex, int... blackRows) {
        OmokBoard board = new OmokBoard();
        for (int row : blackRows) {
            board.place(colIndex, row, Stone.BLACK);
        }
        return board;
    }

    @Test
    void runLengthCountsBothDirections() {
        OmokBoard board = boardWithRow(7, "....XXXXX......");

        assertThat(LineScanner.runLength(board, 6, 7, Axis.HORIZONTAL, Stone.BLACK)).isEqualTo(5);
        assertThat(LineScanner.runLength(board, 4, 7, Axis.HORIZONTAL, Stone.BLACK)).isEqualTo(5);
    }

    @Test
    void runLengthStopsAtTheOpponent() {
        OmokBoard board = boardWithRow(7, "...OXXX........");

        assertThat(LineScanner.runLength(board, 5, 7, Axis.HORIZONTAL, Stone.BLACK)).isEqualTo(3);
    }

    @Test
    void runLengthIsOneForALoneStone() {
        OmokBoard board = boardWithRow(7, ".......X.......");

        assertThat(LineScanner.runLength(board, 7, 7, Axis.HORIZONTAL, Stone.BLACK)).isEqualTo(1);
    }

    @Test
    void runLengthWorksOnTheDiagonal() {
        OmokBoard board = OmokBoard.of(
                "X..............",
                ".X.............",
                "..X............",
                "...X...........",
                "....X..........",
                EMPTY_ROW, EMPTY_ROW, EMPTY_ROW, EMPTY_ROW, EMPTY_ROW,
                EMPTY_ROW, EMPTY_ROW, EMPTY_ROW, EMPTY_ROW, EMPTY_ROW
        );

        assertThat(LineScanner.runLength(board, 2, 2, Axis.DIAGONAL, Stone.BLACK)).isEqualTo(5);
    }

    @Test
    void runLengthWorksOnVerticalAxis() {
        OmokBoard board = boardWithColumn(7, 3, 4, 5, 6, 7);

        assertThat(LineScanner.runLength(board, 7, 5, Axis.VERTICAL, Stone.BLACK)).isEqualTo(5);
    }

    @Test
    void windowsOfFiveGivesFiveWindowsInTheMiddleOfTheBoard() {
        OmokBoard board = new OmokBoard();

        List<int[]> windows = LineScanner.windowsOfFive(board, 7, 7, Axis.HORIZONTAL);

        assertThat(windows).hasSize(5);
        assertThat(windows.getFirst()).containsExactly(3, 7);
        assertThat(windows.getLast()).containsExactly(7, 7);
    }

    @Test
    void windowsOfFiveIsClippedAtTheEdge() {
        OmokBoard board = new OmokBoard();

        List<int[]> windows = LineScanner.windowsOfFive(board, 1, 0, Axis.HORIZONTAL);

        assertThat(windows).hasSize(2);
        assertThat(windows.getFirst()).containsExactly(0, 0);
        assertThat(windows.getLast()).containsExactly(1, 0);
    }

    @Test
    void windowsOfFiveWorksOnVerticalAxis() {
        OmokBoard board = new OmokBoard();

        List<int[]> windows = LineScanner.windowsOfFive(board, 7, 7, Axis.VERTICAL);

        assertThat(windows).hasSize(5);
        assertThat(windows.getFirst()).containsExactly(7, 3);
        assertThat(windows.getLast()).containsExactly(7, 7);
    }

    @Test
    void windowsOfFiveWorksOnDiagonalAxis() {
        OmokBoard board = new OmokBoard();

        List<int[]> windows = LineScanner.windowsOfFive(board, 7, 7, Axis.DIAGONAL);

        assertThat(windows).hasSize(5);
        assertThat(windows.getFirst()).containsExactly(3, 3);
        assertThat(windows.getLast()).containsExactly(7, 7);
    }

    @Test
    void windowsOfFiveClipsAtFarRightEdge() {
        OmokBoard board = new OmokBoard();

        List<int[]> windows = LineScanner.windowsOfFive(board, 13, 7, Axis.HORIZONTAL);

        assertThat(windows).hasSize(2);
        assertThat(windows.getFirst()).containsExactly(9, 7);
        assertThat(windows.getLast()).containsExactly(10, 7);
    }

    @Test
    void fourAxesAreCovered() {
        assertThat(Axis.values()).hasSize(4);
        assertThat(Axis.HORIZONTAL.dx()).isEqualTo(1);
        assertThat(Axis.HORIZONTAL.dy()).isZero();
        assertThat(Axis.VERTICAL.dx()).isZero();
        assertThat(Axis.VERTICAL.dy()).isEqualTo(1);
        assertThat(Axis.DIAGONAL.dx()).isEqualTo(1);
        assertThat(Axis.DIAGONAL.dy()).isEqualTo(1);
        assertThat(Axis.ANTI_DIAGONAL.dx()).isEqualTo(1);
        assertThat(Axis.ANTI_DIAGONAL.dy()).isEqualTo(-1);
    }
}
