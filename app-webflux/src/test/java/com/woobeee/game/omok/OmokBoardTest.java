package com.woobeee.game.omok;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OmokBoardTest {

    @Test
    void newBoardIsEmpty() {
        OmokBoard board = new OmokBoard();

        assertThat(board.at(0, 0)).isEqualTo(Stone.EMPTY);
        assertThat(board.at(14, 14)).isEqualTo(Stone.EMPTY);
        assertThat(board.isEmpty(7, 7)).isTrue();
    }

    @Test
    void placeAndClearRoundTrip() {
        OmokBoard board = new OmokBoard();

        board.place(3, 1, Stone.BLACK);
        assertThat(board.at(3, 1)).isEqualTo(Stone.BLACK);
        assertThat(board.at(1, 3)).isEqualTo(Stone.EMPTY); // Asymmetric: catches x/y transposition

        board.clear(3, 1);
        assertThat(board.at(3, 1)).isEqualTo(Stone.EMPTY);
    }

    @Test
    void boundsAreFifteenBySFifteen() {
        OmokBoard board = new OmokBoard();

        assertThat(OmokBoard.SIZE).isEqualTo(15);
        assertThat(board.inBounds(0, 0)).isTrue();
        assertThat(board.inBounds(14, 14)).isTrue();
        assertThat(board.inBounds(-1, 0)).isFalse();
        assertThat(board.inBounds(15, 0)).isFalse();
        assertThat(board.at(-1, 0)).isEqualTo(Stone.EMPTY);
    }

    @Test
    void copyIsIndependent() {
        OmokBoard board = new OmokBoard();
        board.place(3, 1, Stone.BLACK);

        OmokBoard copy = board.copy();
        copy.place(5, 2, Stone.WHITE);

        assertThat(board.at(5, 2)).isEqualTo(Stone.EMPTY); // Asymmetric: original unaffected
        assertThat(board.at(2, 5)).isEqualTo(Stone.EMPTY); // Additional asymmetric check
        assertThat(copy.at(3, 1)).isEqualTo(Stone.BLACK);
        assertThat(copy.at(5, 2)).isEqualTo(Stone.WHITE);
        assertThat(copy.at(2, 5)).isEqualTo(Stone.EMPTY); // Catches x/y transposition in place
    }

    @Test
    void ofParsesAsciiRowsTopDown() {
        OmokBoard board = OmokBoard.of(
                "...............",
                "...XO..........",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "...............",
                "..............."
        );

        assertThat(board.at(3, 1)).isEqualTo(Stone.BLACK);
        assertThat(board.at(4, 1)).isEqualTo(Stone.WHITE);
        assertThat(board.at(0, 0)).isEqualTo(Stone.EMPTY);
    }

    @Test
    void ofRejectsAWrongShape() {
        assertThatThrownBy(() -> OmokBoard.of("..."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void opposite() {
        assertThat(Stone.BLACK.opposite()).isEqualTo(Stone.WHITE);
        assertThat(Stone.WHITE.opposite()).isEqualTo(Stone.BLACK);
        assertThat(Stone.EMPTY.opposite()).isEqualTo(Stone.EMPTY);
    }
}
