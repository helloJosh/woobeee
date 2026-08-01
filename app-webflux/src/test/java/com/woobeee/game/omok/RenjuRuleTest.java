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

    private Stone[][] snapshot(OmokBoard board) {
        Stone[][] snapshot = new Stone[OmokBoard.SIZE][OmokBoard.SIZE];
        for (int y = 0; y < OmokBoard.SIZE; y++) {
            for (int x = 0; x < OmokBoard.SIZE; x++) {
                snapshot[y][x] = board.at(x, y);
            }
        }
        return snapshot;
    }

    /** GAME-AC-10 — 장목 */
    @Test
    void sixInARowIsOverline() {
        OmokBoard board = rowBoard("...XXX.XX......");

        assertThat(RenjuRule.judge(board, 6, 7)).isEqualTo(RenjuRule.Verdict.OVERLINE);
    }

    /**
     * GAME-AC-10 — 정확히 5는 승리이므로 금수보다 우선한다.
     *
     * <p>가로: 3,4,_,6,7 에 흑 — (5,7) 을 채우면 3..7 이 정확히 5(승리)다. 이 수는 동시에
     * 세로(4,5,6,7)와 대각선(2,4)-(5,7) 두 방향으로 "사 모양"도 만든다 — 5-먼저 규칙이 없다면
     * 사사(금수)로 오판할 자리다. 손으로 확인: 세로는 4,5,6 이 이미 흑이고 (5,7) 을 채우면
     * 4-7 네 칸이 연속, 양끝(3,8)이 비어 있어 진짜 사다. 대각선은 (2,4)(3,5)(4,6) 이 이미
     * 흑이고 (5,7) 을 채우면 (2,4)-(5,7) 네 칸이 연속, 양끝((1,3),(6,8))이 비어 있어 이것도
     * 진짜 사다. 가로 자체는 이미 정확히 5가 되어버려 5칸 창(사 후보)으로 셀 게 없다 — 어느
     * 창을 채워도 6(장목) 이상이 되기 때문이다. 그래서 사만 세면 세로+대각선=2 로 사사가
     * 되지만, 5가 먼저 확인되므로 결과는 LEGAL 이다.
     */
    @Test
    void exactlyFiveIsLegalEvenIfItWouldOtherwiseBeForbidden() {
        OmokBoard board = new OmokBoard();
        // 가로: (5,7) 을 채우면 3..7 이 정확히 5
        board.place(3, 7, Stone.BLACK);
        board.place(4, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(7, 7, Stone.BLACK);
        // 세로: (5,7) 을 채우면 4,5,6,7 네 칸이 연속(양끝 열림) — 사 하나
        board.place(5, 4, Stone.BLACK);
        board.place(5, 5, Stone.BLACK);
        board.place(5, 6, Stone.BLACK);
        // 대각선: (5,7) 을 채우면 (2,4)-(5,7) 네 칸이 연속(양끝 열림) — 사 하나 더
        board.place(2, 4, Stone.BLACK);
        board.place(3, 5, Stone.BLACK);
        board.place(4, 6, Stone.BLACK);

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

    /** GAME-AC-10 — 삼삼(서로 다른 두 축) */
    @Test
    void twoOpenThreesAreDoubleThree() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.DOUBLE_THREE);
    }

    /**
     * GAME-AC-10 — 삼삼(같은 축 위의 서로 다른 두 삼).
     *
     * <p>가로 한 줄에 2,3 과 7,8 이 흑이고 5 를 놓는다. 손으로 확인: 왼쪽 삼{2,3,5} 는 4 를
     * 채우면 2..5 네 칸이 연속(양끝 1,6 이 비어 있어 진짜 사)이 되고, 오른쪽 삼{5,7,8} 은 6
     * 을 채우면 5..8 네 칸이 연속(양끝 4,9 가 비어 있어 진짜 사)이 된다 — 두 완성점(4,6) 모두
     * 판이 비어 있어 그 자체로 막힐 이유가 없으니 둘 다 합법이다. 서로 다른 세 돌 집합
     * {2,3,5} 와 {5,7,8} 이 모두 (5,7) 을 포함하는 진짜 열린삼이므로, 한 축에서만 삼삼이
     * 성립해 DOUBLE_THREE 다. {@link FourRule} 이 한 축의 사사를 완성점이 아니라 사 집합으로
     * 세는 것과 같은 이유로, 삼도 완성점이 아니라 집합으로 세야 이 축 하나로도 셋 수 있다.
     */
    @Test
    void twoDistinctThreeGroupsOnOneAxisAreDoubleThree() {
        OmokBoard board = new OmokBoard();
        board.place(2, 7, Stone.BLACK);
        board.place(3, 7, Stone.BLACK);
        board.place(7, 7, Stone.BLACK);
        board.place(8, 7, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 5, 7)).isEqualTo(RenjuRule.Verdict.DOUBLE_THREE);
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

    /**
     * GAME-AC-13 — 어떤 삼의 유일한 사 완성점이 그 자체로 금수면, 그 삼은 열려 있지 않다.
     *
     * <p>가로 삼은 5,6,(7,7). 왼쪽 완성점(4,7)은 그 바깥쪽 플랭크(3,7)를 백으로 막아 애초에
     * 사 모양이 되지 않는다(손으로 확인: 4 를 채워도 head=4, 그 앞칸 3 이 백이라
     * {@link FourRule#makesStraightFour} 의 "양끝이 빈칸" 조건이 깨진다). 오른쪽 완성점
     * (8,7)은 모양은 진짜 사이지만(5,6,7,8 네 칸 연속, 양끝 4,9 가 비어 있음), 그 자리에
     * 흑을 두면 세로(8,4)(8,5)(8,6) 과 만나 4,5,6,7 네 칸도 연속시켜 버려 사가 하나 더
     * 생긴다 — 즉 (8,7) 은 그 자체로 사사라 금수다. 그래서 가로 삼은 완성점이 하나도 열리지
     * 않아 열린삼이 아니다. 세로 삼(7,5)(7,6)(7,7)은 방해 없이 정상적으로 열려 있으므로,
     * 열린삼은 총 하나뿐이고 (7,7) 은 삼삼이 아니라 LEGAL 이다.
     *
     * <p>이 테스트가 바로 재귀가 실제로 결과를 바꾸는 자리다: 재귀 깊이 제한을 1로 낮추면
     * (8,7) 을 판정하는 재귀 호출(깊이 1)이 사사 검사를 하기도 전에 깊이 제한에 걸려 LEGAL
     * 을 반환해 버리고, 그러면 가로 삼도 열린 것으로 잘못 세어 DOUBLE_THREE 로 오판하게 된다.
     */
    @Test
    void aThreeIsNotOpenWhenItsOnlyStraightFourPointIsForbidden() {
        OmokBoard board = new OmokBoard();

        // 가로 삼: (5,7)(6,7) + 놓을 자리 (7,7). 왼쪽 완성점(4,7)의 바깥 플랭크(3,7)를 막는다.
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);
        board.place(3, 7, Stone.WHITE);

        // 세로 삼(x=8): (8,7) 을 채우면 이 세 돌과 만나 세로로도 사가 되어, (8,7) 자체가 사사다.
        board.place(8, 4, Stone.BLACK);
        board.place(8, 5, Stone.BLACK);
        board.place(8, 6, Stone.BLACK);

        // 세로 삼: (7,5)(7,6) + 놓을 자리 (7,7). 방해 없이 정상적으로 열려 있다.
        board.place(7, 5, Stone.BLACK);
        board.place(7, 6, Stone.BLACK);

        // (7,7) 이 실제로 놓인 상태를 재현해 (8,7) 이 그 자체로 사사(금수)임을 직접 확인한다 —
        // 재귀 안에서 일어나는 것과 같은 검사다.
        board.place(7, 7, Stone.BLACK);
        assertThat(RenjuRule.isForbidden(board, 8, 7)).isTrue();
        board.clear(7, 7);

        // 그래서 가로 삼은 열리지 않고, 세로 삼 하나만 열려 있어 (7,7) 은 삼삼이 아니라 합법이다.
        assertThat(RenjuRule.judge(board, 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    /**
     * GAME-AC-13 — 이번 수와 무관하게 판 어딘가에 있던 삼이 우연히 사 모양을 완성해도 세면
     * 안 된다.
     *
     * <p>(8,7)(9,7)(10,7) 은 이번 수 (5,7) 과 아무 관련이 없다. 그런데 (7,7) 을 채우면
     * 7,8,9,10 네 칸이 연속되어 모양만 보면 사(완성점 7)로 보인다 — 하지만 이 사의 세 돌
     * {8,9,10} 은 지금 놓는 자리 (5,7) 을 전혀 포함하지 않으므로 이번 수의 삼으로 셀 수 없다.
     * 세로로는 (5,5)(5,6) + (5,7) 이 진짜 열린삼 하나를 만든다. 열린삼 총합은 1(세로) 뿐이라
     * 삼삼에 못 미치고, 가로에는 4/5 도 없으므로(사 자체가 없음) LEGAL 이다.
     */
    @Test
    void aThreeElsewhereOnTheAxisIsNotCountedAsOpenForAnUnrelatedMove() {
        OmokBoard board = new OmokBoard();
        board.place(8, 7, Stone.BLACK);
        board.place(9, 7, Stone.BLACK);
        board.place(10, 7, Stone.BLACK);
        board.place(5, 5, Stone.BLACK);
        board.place(5, 6, Stone.BLACK);

        assertThat(RenjuRule.judge(board, 5, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    @Test
    void anEmptyBoardCentreIsLegal() {
        assertThat(RenjuRule.judge(new OmokBoard(), 7, 7)).isEqualTo(RenjuRule.Verdict.LEGAL);
    }

    /**
     * 판 전체를 놓기 전/후로 스냅샷해 비교한다 — {@code (7,7)} 한 칸만 보면 {@code isOpenThree}
     * 내부에서 완성점 시험 후 지우는 것을 빼먹어도(예: {@code board.clear(ex, ey)} 누락) 그
     * 칸은 애초에 시험 대상이 아니므로 통과해버린다. 열린삼 완성점 후보 칸들까지 포함해 판
     * 전체가 원상태인지 확인해야 그런 누수를 잡는다.
     */
    @Test
    void judgeDoesNotMutateTheBoard() {
        OmokBoard board = new OmokBoard();
        board.place(5, 7, Stone.BLACK);
        board.place(6, 7, Stone.BLACK);

        Stone[][] before = snapshot(board);
        RenjuRule.judge(board, 7, 7);
        Stone[][] after = snapshot(board);

        assertThat(after).isDeepEqualTo(before);
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
