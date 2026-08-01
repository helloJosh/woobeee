package com.woobeee.game.omok;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FourRuleTest {

    private static final String E = "...............";

    private OmokBoard rowBoard(String row) {
        String[] rows = new String[OmokBoard.SIZE];
        for (int i = 0; i < OmokBoard.SIZE; i++) {
            rows[i] = i == 7 ? row : E;
        }
        return OmokBoard.of(rows);
    }

    /**
     * .XXXX. 는 양끝 어디에 놓아도 5가 되지만, 완성되는 4개 돌 집합이 같으므로 사는 하나다.
     * 완성점을 세면 2가 나와 사사로 오판한다 — 이 테스트가 그 실수를 막는다.
     */
    @Test
    void straightFourIsCountedOnceNotTwice() {
        OmokBoard board = rowBoard("....XXXX.......");

        assertThat(FourRule.countFours(board, 6, 7, Axis.HORIZONTAL)).isEqualTo(1);
    }

    @Test
    void blockedFourIsStillOneFour() {
        OmokBoard board = rowBoard("...OXXXX.......");

        assertThat(FourRule.countFours(board, 6, 7, Axis.HORIZONTAL)).isEqualTo(1);
    }

    @Test
    void splitFourIsOneFour() {
        OmokBoard board = rowBoard("....XX.XX......");

        assertThat(FourRule.countFours(board, 5, 7, Axis.HORIZONTAL)).isEqualTo(1);
    }

    @Test
    void twoSeparateFourGroupsOnOneAxisCountAsTwo() {
        // X X X . X . X X X  -> 가운데 빈칸을 채우는 서로 다른 4개-집합이 둘 있다
        OmokBoard board = rowBoard("...XXX.X.XXX...");

        assertThat(FourRule.countFours(board, 7, 7, Axis.HORIZONTAL)).isEqualTo(2);
    }

    /**
     * 창의 모양만으로는 부족하다 — 빈칸을 채웠을 때 정확히 5가 되는지까지 확인해야 한다.
     * ...XXXX.X...... 에서 (5,7) 판정 시: 창 2..6 은 채우면 정확히 5(진짜 사)이지만,
     * 창 3..7 은 8번 칸도 흑이라 채우면 6(장목)이 되어 사가 아니다. 모양만 보면 둘 다
     * 4흑+1빈으로 보여 오답(2)을 낸다.
     */
    @Test
    void windowMustCompleteToExactlyFiveNotOverline() {
        OmokBoard board = rowBoard("...XXXX.X......");

        assertThat(FourRule.countFours(board, 5, 7, Axis.HORIZONTAL)).isEqualTo(1);
    }

    @Test
    void aThreeIsNotAFour() {
        OmokBoard board = rowBoard("....XXX........");

        assertThat(FourRule.countFours(board, 5, 7, Axis.HORIZONTAL)).isZero();
    }

    @Test
    void fiveInARowIsNotCountedAsAFour() {
        OmokBoard board = rowBoard("....XXXXX......");

        assertThat(FourRule.countFours(board, 6, 7, Axis.HORIZONTAL)).isZero();
    }

    @Test
    void straightFourNeedsBothFlanksEmpty() {
        assertThat(FourRule.makesStraightFour(rowBoard("....XXXX......."), 6, 7, Axis.HORIZONTAL)).isTrue();
        assertThat(FourRule.makesStraightFour(rowBoard("...OXXXX......."), 6, 7, Axis.HORIZONTAL)).isFalse();
        assertThat(FourRule.makesStraightFour(rowBoard("...OXXXXO......"), 6, 7, Axis.HORIZONTAL)).isFalse();
    }

    @Test
    void straightFourNeedsFlanksInsideTheBoard() {
        OmokBoard board = rowBoard("XXXX...........");

        assertThat(FourRule.makesStraightFour(board, 1, 7, Axis.HORIZONTAL)).isFalse();
    }

    @Test
    void splitShapeIsNotAStraightFour() {
        OmokBoard board = rowBoard("....XX.XX......");

        assertThat(FourRule.makesStraightFour(board, 5, 7, Axis.HORIZONTAL)).isFalse();
    }

    /**
     * 모양만으로는 부족하다 — makesStraightFour 도 countFours 와 같은 완성점 검증이 필요하다.
     * ..X.XXXX.X..... 에서 흑 넷(4..7)의 양끝(3, 8)이 모두 판 안의 빈칸이라 모양은
     * 열린사처럼 보이지만, 왼쪽(3)을 채우면 2번 칸의 흑과 이어져 2..7 = 6(장목)이고 오른쪽(8)을
     * 채워도 9번 칸의 흑과 이어져 4..9 = 6(장목)이다. 양끝 어디로도 정확히 5를 만들 수 없으므로
     * 이 사는 승리점이 하나도 없다 — 열린사가 아니다. 이 검증이 없으면 RenjuRule.countOpenThrees
     * 가 이것을 열린사로 오인해 합법적인 삼을 열린삼으로, 나아가 합법수를 DOUBLE_THREE 로
     * 잘못 거절한다.
     */
    @Test
    void bothFlanksCompletingToOverlineIsNotAStraightFour() {
        OmokBoard board = rowBoard("..X.XXXX.X.....");

        assertThat(FourRule.makesStraightFour(board, 6, 7, Axis.HORIZONTAL)).isFalse();
    }
}
