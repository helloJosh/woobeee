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
     * ...XXXX.X...... 에서 (5,7) 판정 시: 창 3..7 은 채우면 정확히 5(진짜 사)이지만,
     * 창 4..8 은 채우면 6(장목)이 되어 사가 아니다. 모양만 보면 둘 다 4흑+1빈으로 보여
     * 오답(2)을 낸다.
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
}
