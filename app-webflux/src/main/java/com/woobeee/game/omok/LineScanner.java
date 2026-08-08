package com.woobeee.game.omok;

import java.util.ArrayList;
import java.util.List;

public final class LineScanner {
    private static final int FIVE = 5;

    private LineScanner() {
    }

    /** (x,y) 를 포함해 axis 방향으로 이어진 stone 의 개수. (x,y) 자체가 stone 이 아니면 0. */
    public static int runLength(OmokBoard board, int x, int y, Axis axis, Stone stone) {
        if (board.at(x, y) != stone) {
            return 0;
        }

        int count = 1;
        count += walk(board, x, y, axis.dx(), axis.dy(), stone);
        count += walk(board, x, y, -axis.dx(), -axis.dy(), stone);
        return count;
    }

    /** (x,y) 를 포함하는 5칸 윈도우의 시작점 목록. 판 밖으로 나가는 윈도우는 뺀다. */
    public static List<int[]> windowsOfFive(OmokBoard board, int x, int y, Axis axis) {
        List<int[]> windows = new ArrayList<>(FIVE);

        for (int offset = 0; offset < FIVE; offset++) {
            int startX = x - offset * axis.dx();
            int startY = y - offset * axis.dy();
            int endX = startX + (FIVE - 1) * axis.dx();
            int endY = startY + (FIVE - 1) * axis.dy();

            if (board.inBounds(startX, startY) && board.inBounds(endX, endY)) {
                windows.add(new int[]{startX, startY});
            }
        }

        windows.sort((a, b) -> {
            int byX = Integer.compare(a[0], b[0]);
            return byX != 0 ? byX : Integer.compare(a[1], b[1]);
        });
        return windows;
    }

    private static int walk(OmokBoard board, int x, int y, int dx, int dy, Stone stone) {
        int count = 0;
        int cx = x + dx;
        int cy = y + dy;
        while (board.inBounds(cx, cy) && board.at(cx, cy) == stone) {
            count++;
            cx += dx;
            cy += dy;
        }
        return count;
    }
}
