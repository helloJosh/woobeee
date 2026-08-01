package com.woobeee.game.omok;

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
 */
public final class FourRule {
    private static final int FIVE = 5;

    private FourRule() {
    }

    public static int countFours(OmokBoard board, int x, int y, Axis axis) {
        Set<String> distinctGroups = new HashSet<>();

        // (x,y) already sits inside a contiguous run of 5+ black stones: every 5-cell
        // window through it either lies fully inside the run (5 blacks, no empty) or
        // extends the run into an overline (6+), never a genuine, winnable four.
        if (LineScanner.runLength(board, x, y, axis, Stone.BLACK) >= FIVE) {
            return 0;
        }

        for (int[] window : LineScanner.windowsOfFive(board, x, y, axis)) {
            List<int[]> blacks = new java.util.ArrayList<>(4);
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
                } else {
                    empties = -1;
                    break;
                }
            }

            if (empties != 1 || blacks.size() != 4 || !containsPlaced) {
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
