package com.woobeee.game.omok;

/**
 * 승리 판정. 렌주룰이라 색깔마다 다르다.
 *
 * <p>흑은 정확히 5목일 때만 이긴다 — 6목 이상은 장목이라 금수다.
 * 백은 금수가 없으므로 5목 이상이면 이긴다.
 */
public final class WinRule {
    private static final int FIVE = 5;

    private WinRule() {
    }

    public static boolean isWin(OmokBoard board, int x, int y, Stone stone) {
        for (Axis axis : Axis.values()) {
            int length = LineScanner.runLength(board, x, y, axis, stone);
            if (stone == Stone.BLACK ? length == FIVE : length >= FIVE) {
                return true;
            }
        }
        return false;
    }
}
