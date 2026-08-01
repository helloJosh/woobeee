package com.woobeee.game.omok;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RenjuRuleTest {

    private static final String E = "...............";

    private OmokBoard rowBoard(String row) {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = i == 7 ? row : E;
        }
        return OmokBoard.of(rows);
    }

    /** GAME-AC-10 — 장목 */
    @Test
    void sixInARowIsOverline() {
        OmokBoard board = rowBoard("...XXX.XX......");

        assertThat(RenjuRule.judge(board, 6, 7)).isEqualTo(RenjuRule.Verdict.OVERLINE);
    }

    /** GAME-AC-10 — 정확히 5는 승리이므로 금수가 아니다 */
    @Test
    void exactlyFiveIsLegalEvenIfItWouldOtherwiseBeForbidden() {
        OmokBoard board = rowBoard("...XX.XX.......");

        assertThat(RenjuRule.judge(board, 5, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    /** GAME-AC-10 — 사사 */
    @Test
    void twoFoursOnDifferentAxesAreDoubleFour() {
        OmokBoard board = new OmokBoard();

        // 가로: (5,7)(6,7) _ (8,7)(9,7) — (7,7) 을 채우면 5칸 윈도우에 흑 4 + 빈칸 1 이 된다
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(8, 7, Stone.BLACK);
        // 세로: (7,5)(7,6) _ (7,8)(7,9)
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);
        board.place(7, 8, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.DOUBLE_FOUR);
    }

    /** GAME-AC-10 — 삼삼 */
    @Test
    void twoOpenThreesAreDoubleThree() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.DOUBLE_THREE);
    }

    @Test
    void oneOpenThreeIsLegal() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    @Test
    void aBlockedThreeIsNotOpenSoTwoOfThemAreLegal() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(4, 7, Stone.WHITE);
        board.place(8, 7, Stone.WHITE);
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);
        board.place(7, 4, Stone.WHITE);
        board.place(7, 8, Stone.WHITE);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    /** GAME-AC-13 — 열린사를 만드는 자리가 금수면 그 삼은 열린삼이 아니다 */
    @Test
    void aThreeIsNotOpenWhenTheStraightFourPointIsItselfForbidden() {
        OmokBoard board = new OmokBoard();

        // 가로 삼: (5,7)(6,7) + 놓을 자리 (7,7)
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        // 세로 삼: (7,5)(7,6)
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);

        // (7,7) 은 삼삼이므로 금수다. 이 판정 자체가 재귀를 한 단계 태운다.
        assertThat(RenjuRule.isForbidden(board, 7, 7)).isTrue();

        // 재귀가 무한히 돌지 않고 끝난다는 것도 확인한다.
        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.DOUBLE_THREE);
    }

    @Test
    void anEmptyBoardCentreIsLegal() {
        assertThat(RenjuRule.judge(new OmokBoard(), 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    @Test
    void judgeDoesNotMutateTheBoard() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);

        RenjuRule.judge(board, 7, 7);

        assertThat(board.at(7, 7)).isEqualTo(Stone.EMPTY);
    }

    @Test
    void whiteIsNeverJudged() {
        // judge 는 흑 전용이다. 백 착수는 OmokGame 이 아예 부르지 않는다.
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.WHITE);
        board.place(6, 7, Stone.WHITE);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }
}
