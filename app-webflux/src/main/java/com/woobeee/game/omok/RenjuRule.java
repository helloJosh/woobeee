package com.woobeee.game.omok;

/**
 * 렌주룰 금수 판정. 흑 전용이다 — 백은 금수가 없다.
 *
 * <p>열린삼 판정이 금수 판정을 다시 부른다. 삼이 "열려" 있으려면 그 삼을 열린사로 바꾸는 수가
 * 실제로 둘 수 있는 수여야 하고, 그 수가 금수인지 보려면 다시 금수 판정이 필요하기 때문이다.
 * 재귀는 {@link #MAX_DEPTH} 에서 끊는다 — 그 깊이를 넘는 중첩 금수는 실전에서 나오지 않고,
 * 끊지 않으면 서로 물린 두 자리에서 무한히 돈다.
 */
public final class RenjuRule {
    public static final int MAX_DEPTH = 5;

    private static final int FIVE = 5;

    public enum Verdict {
        LEGAL,
        OVERLINE,
        DOUBLE_FOUR,
        DOUBLE_THREE
    }

    private RenjuRule() {
    }

    public static boolean isForbidden(OmokBoard board, int x, int y) {
        return judge(board, x, y) != Verdict.LEGAL;
    }

    public static Verdict judge(OmokBoard board, int x, int y) {
        return judge(board, x, y, 0);
    }

    private static Verdict judge(OmokBoard board, int x, int y, int depth) {
        if (!board.isEmpty(x, y)) {
            return Verdict.LEGAL;
        }
        if (depth >= MAX_DEPTH) {
            return Verdict.LEGAL;
        }

        board.place(x, y, Stone.BLACK);
        try {
            // 정확히 5는 승리다. 승리는 금수보다 우선한다.
            for (Axis axis : Axis.values()) {
                if (LineScanner.runLength(board, x, y, axis, Stone.BLACK) == FIVE) {
                    return Verdict.LEGAL;
                }
            }

            for (Axis axis : Axis.values()) {
                if (LineScanner.runLength(board, x, y, axis, Stone.BLACK) > FIVE) {
                    return Verdict.OVERLINE;
                }
            }

            int fours = 0;
            for (Axis axis : Axis.values()) {
                fours += FourRule.countFours(board, x, y, axis);
            }
            if (fours >= 2) {
                return Verdict.DOUBLE_FOUR;
            }

            int openThrees = 0;
            for (Axis axis : Axis.values()) {
                if (isOpenThree(board, x, y, axis, depth)) {
                    openThrees++;
                }
            }
            if (openThrees >= 2) {
                return Verdict.DOUBLE_THREE;
            }

            return Verdict.LEGAL;
        } finally {
            board.clear(x, y);
        }
    }

    /**
     * (x,y) 에 흑이 놓인 상태에서, 그 축의 삼이 열린삼인가.
     *
     * <p>축 위의 빈칸 e 중 하나에 흑을 더 놓아 열린사가 되고, 그 e 가 금수가 아니면 열린삼이다.
     */
    private static boolean isOpenThree(OmokBoard board, int x, int y, Axis axis, int depth) {
        for (int offset = -4; offset <= 4; offset++) {
            if (offset == 0) {
                continue;
            }

            int ex = x + offset * axis.dx();
            int ey = y + offset * axis.dy();
            if (!board.isEmpty(ex, ey)) {
                continue;
            }

            board.place(ex, ey, Stone.BLACK);
            boolean straightFour = FourRule.makesStraightFour(board, ex, ey, axis);
            board.clear(ex, ey);

            if (!straightFour) {
                continue;
            }

            if (judge(board, ex, ey, depth + 1) == Verdict.LEGAL) {
                return true;
            }
        }
        return false;
    }
}
