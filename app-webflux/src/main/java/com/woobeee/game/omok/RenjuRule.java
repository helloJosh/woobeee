package com.woobeee.game.omok;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 렌주룰 금수 판정. 흑 전용이다 — 백은 금수가 없다.
 *
 * <p>열린삼 판정이 금수 판정을 다시 부른다. 삼이 "열려" 있으려면 그 삼을 열린사로 바꾸는 수가
 * 실제로 둘 수 있는 수여야 하고, 그 수가 금수인지 보려면 다시 금수 판정이 필요하기 때문이다.
 * 재귀는 {@link #MAX_DEPTH} 에서 끊는다 — 그 깊이를 넘는 중첩 금수는 실전에서 나오지 않고,
 * 끊지 않으면 서로 물린 두 자리에서 무한히 돈다.
 *
 * <p><b>삼도 사와 마찬가지로 "완성점"이 아니라 "돌 세 개의 집합"으로 센다.</b>
 * {@link FourRule#countFours} 가 완성점을 세면 사사를 오판하듯, 삼도 한 축에 완성점이 둘(양쪽
 * 끝) 있어도 같은 세 돌 집합이면 삼은 하나다. 게다가 그 완성점이 만드는 사가 지금 놓은 자리
 * {@code (x,y)} 를 포함하지 않으면 애초에 이번 수와 무관한 삼이므로 세면 안 된다 — 판 어딘가에
 * 이미 있던 삼이 우연히 사 모양을 완성할 수 있다는 이유만으로 이번 수를 삼삼으로 몰아서는
 * 안 된다.
 */
public final class RenjuRule {
    public static final int MAX_DEPTH = 5;

    private static final int FIVE = 5;
    private static final int FOUR = 4;

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
            // 이 노드 자체에 대해서는 "막힌 것을 못 찾았으니 LEGAL" 이라는 허용적인 판단이다.
            // 하지만 이 판정은 보통 부모의 열린삼 완성점 검사 안에서 호출된 것이라, 그 결과는
            // 부모 쪽에서는 반대 방향으로 작동한다 — "이 완성점은 금수가 아니다" → 부모의 삼이
            // 열린삼 쪽으로 기운다. 즉 깊이가 한 단계 늘 때마다 허용/금지의 부호가 뒤집히므로,
            // "깊이 제한에서는 항상 관대하게 처리한다" 정도로 단순하게 읽으면 잘못 이해하기 쉽다.
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
                openThrees += countOpenThrees(board, x, y, axis, depth);
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
     * (x,y) 에 흑이 놓인 상태에서, 그 축 위에 있는 "(x,y) 를 포함하는" 서로 다른 열린삼의 개수.
     *
     * <p>축 위의 빈칸 e 마다: e 에 흑을 놓아 사가 되고(양끝이 모두 판 안의 빈칸), 그 사가
     * {@code (x,y)} 를 포함하고, e 가 그 자체로 금수가 아니면, e 를 완성점으로 하는 삼이 하나
     * 열려 있는 것이다. 그 삼(사에서 e 를 뺀 세 돌)을 집합으로 모아 중복 없이 센다 — 같은 삼이
     * 양쪽 끝 어디를 완성점으로 잡아도 같은 세 돌 집합으로 접히기 때문이다({@link FourRule}
     * 이 사를 셀 때와 같은 이유).
     */
    private static int countOpenThrees(OmokBoard board, int x, int y, Axis axis, int depth) {
        Set<String> distinctGroups = new HashSet<>();

        for (int offset = -4; offset <= 4; offset++) {
            if (offset == 0) {
                continue;
            }

            int ex = x + offset * axis.dx();
            int ey = y + offset * axis.dy();
            if (!board.isEmpty(ex, ey)) {
                continue;
            }

            List<int[]> three = new ArrayList<>(3);
            boolean containsPlaced = false;

            board.place(ex, ey, Stone.BLACK);
            try {
                if (!FourRule.makesStraightFour(board, ex, ey, axis)) {
                    continue;
                }

                int headX = ex;
                int headY = ey;
                while (board.inBounds(headX - axis.dx(), headY - axis.dy())
                        && board.at(headX - axis.dx(), headY - axis.dy()) == Stone.BLACK) {
                    headX -= axis.dx();
                    headY -= axis.dy();
                }

                for (int i = 0; i < FOUR; i++) {
                    int cx = headX + i * axis.dx();
                    int cy = headY + i * axis.dy();
                    if (cx == ex && cy == ey) {
                        continue;
                    }
                    three.add(new int[]{cx, cy});
                    if (cx == x && cy == y) {
                        containsPlaced = true;
                    }
                }
            } finally {
                board.clear(ex, ey);
            }

            // 이 사가 지금 놓은 자리와 무관하면(다른 곳에 있던 삼이 우연히 사 모양을 완성한
            // 것이면) 이번 수의 열린삼으로 세지 않는다.
            if (!containsPlaced) {
                continue;
            }

            // 완성점 e 가 그 자체로 금수면 이 삼은 이 방향으로는 "열려" 있지 않다. 이 호출이
            // 바로 열린삼 판정이 금수 판정을 재귀적으로 부르는 지점이다.
            if (judge(board, ex, ey, depth + 1) != Verdict.LEGAL) {
                continue;
            }

            distinctGroups.add(threeKey(three));
        }

        return distinctGroups.size();
    }

    private static String threeKey(List<int[]> cells) {
        Set<String> sorted = new TreeSet<>();
        for (int[] cell : cells) {
            sorted.add(cell[0] + "," + cell[1]);
        }
        return String.join("|", sorted);
    }
}
