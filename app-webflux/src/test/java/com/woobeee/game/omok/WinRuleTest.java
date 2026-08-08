package com.woobeee.game.omok;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WinRuleTest {

    private static final String E = "...............";

    private OmokBoard rowBoard(String row) {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = i == 7 ? row : E;
        }
        return OmokBoard.of(rows);
    }

    /** GAME-AC-12 */
    @Test
    void blackWinsOnExactlyFive() {
        OmokBoard board = rowBoard("....XXXXX......");

        assertThat(WinRule.isWin(board, 6, 7, Stone.BLACK)).isTrue();
    }

    /** GAME-AC-12 */
    @Test
    void blackDoesNotWinOnSix() {
        OmokBoard board = rowBoard("....XXXXXX.....");

        assertThat(WinRule.isWin(board, 6, 7, Stone.BLACK)).isFalse();
    }

    /** GAME-AC-11 */
    @Test
    void whiteWinsOnFive() {
        OmokBoard board = rowBoard("....OOOOO......");

        assertThat(WinRule.isWin(board, 6, 7, Stone.WHITE)).isTrue();
    }

    /** GAME-AC-11 */
    @Test
    void whiteWinsOnSix() {
        OmokBoard board = rowBoard("....OOOOOO.....");

        assertThat(WinRule.isWin(board, 6, 7, Stone.WHITE)).isTrue();
    }

    @Test
    void fourIsNotAWin() {
        OmokBoard board = rowBoard("....XXXX.......");

        assertThat(WinRule.isWin(board, 6, 7, Stone.BLACK)).isFalse();
    }

    @Test
    void winsOnTheVerticalAxis() {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = (i >= 3 && i <= 7) ? "......X........" : E;
        }
        OmokBoard board = OmokBoard.of(rows);

        assertThat(WinRule.isWin(board, 6, 5, Stone.BLACK)).isTrue();
    }

    @Test
    void winsOnTheAntiDiagonal() {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = E;
        }
        // (4,8) (5,7) (6,6) (7,5) (8,4)
        for (int i = 0; i < 5; i++) {
            int x = 4 + i;
            int y = 8 - i;
            char[] chars = rows[y].toCharArray();
            chars[x] = 'X';
            rows[y] = new String(chars);
        }
        OmokBoard board = OmokBoard.of(rows);

        assertThat(WinRule.isWin(board, 6, 6, Stone.BLACK)).isTrue();
    }
}
