package com.woobeee.game.omok;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 사(four) 판정.
 *
 * <p><b>사는 "완성점"이 아니라 "네 개 돌의 집합"으로 센다.</b> {@code .XXXX.} 는 양끝 어디에
 * 놓아도 5가 되지만 완성되는 돌 집합이 같으므로 사 하나다. 완성점을 세면 2가 되어 사사 금수로
 * 잘못 판정한다 — 렌주 구현이 가장 흔히 틀리는 지점이다.
 *
 * <p><b>창의 모양만으로는 부족하다.</b> 5칸 창에 흑 4 + 빈칸 1 이 있어도, 그 빈칸을 채웠을 때
 * 실제로 정확히 5가 되는지 확인해야 한다. {@code ...XXXX.X......} 에서 (5,7) 을 볼 때 창
 * 2..6(흑 {3,4,5,6}, 빈칸2)은 채우면 정확히 5지만, 창 3..7(흑 {3,4,5,6}, 빈칸7)은 8번 칸도
 * 흑이라 채우면 3..8 이 되어 6(장목)이다 — 모양만 보면 둘 다 통과하지만 후자는 사가 아니다.
 * 그래서 각 창은 후보 빈칸에 흑을 임시로 놓고 {@link LineScanner#runLength} 로 실제 결과
 * 길이가 5인지 확인한 뒤 반드시 원상복구한다.
 */
public final class FourRule {
    private static final int FIVE = 5;

    private FourRule() {
    }

    public static int countFours(OmokBoard board, int x, int y, Axis axis) {
        Set<String> distinctGroups = new HashSet<>();

        for (int[] window : LineScanner.windowsOfFive(board, x, y, axis)) {
            List<int[]> blacks = new ArrayList<>(4);
            int emptyX = -1;
            int emptyY = -1;
            int empties = 0;
            boolean containsPlaced = false;

            for (int i = 0; i < FIVE; i++) {
                int cx = window[0] + i * axis.dx();
                int cy = window[1] + i * axis.dy();
                Stone stone = board.at(cx, cy);

                if (stone == Stone.BLACK) {
                    blacks.add(new int[]{cx, cy});
                    if (cx == x && cy == y) {
                        containsPlaced = true;
                    }
                } else if (stone == Stone.EMPTY) {
                    empties++;
                    emptyX = cx;
                    emptyY = cy;
                } else {
                    empties = -1;
                    break;
                }
            }

            if (empties != 1 || blacks.size() != 4 || !containsPlaced) {
                continue;
            }

            // 모양만으로는 부족하다: 빈칸을 흑으로 임시로 채워보고 실제로 정확히 5가 되는지
            // 확인한다. 6 이상(장목)이면 사가 아니다. 판은 어떤 경로로 빠져나가든 반드시
            // 원상복구한다 — 돌이 남으면 이후의 모든 판정이 오염된다.
            int completedLength;
            board.place(emptyX, emptyY, Stone.BLACK);
            try {
                completedLength = LineScanner.runLength(board, emptyX, emptyY, axis, Stone.BLACK);
            } finally {
                board.clear(emptyX, emptyY);
            }

            if (completedLength != FIVE) {
                continue;
            }

            distinctGroups.add(groupKey(blacks));
        }

        return distinctGroups.size();
    }

    /** 열린사: axis 방향으로 흑 4개가 연속이고 양쪽 바깥이 모두 판 안의 빈칸. */
    public static boolean makesStraightFour(OmokBoard board, int x, int y, Axis axis) {
        if (LineScanner.runLength(board, x, y, axis, Stone.BLACK) != 4) {
            return false;
        }

        int headX = x;
        int headY = y;
        while (board.inBounds(headX - axis.dx(), headY - axis.dy())
                && board.at(headX - axis.dx(), headY - axis.dy()) == Stone.BLACK) {
            headX -= axis.dx();
            headY -= axis.dy();
        }

        int beforeX = headX - axis.dx();
        int beforeY = headY - axis.dy();
        int afterX = headX + 4 * axis.dx();
        int afterY = headY + 4 * axis.dy();

        return board.isEmpty(beforeX, beforeY) && board.isEmpty(afterX, afterY);
    }

    private static String groupKey(List<int[]> blacks) {
        Set<String> sorted = new TreeSet<>();
        for (int[] cell : blacks) {
            sorted.add(cell[0] + "," + cell[1]);
        }
        return String.join("|", sorted);
    }
}
