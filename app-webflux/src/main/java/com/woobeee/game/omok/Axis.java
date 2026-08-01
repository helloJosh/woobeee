package com.woobeee.game.omok;

public enum Axis {
    HORIZONTAL(1, 0),
    VERTICAL(0, 1),
    DIAGONAL(1, 1),
    ANTI_DIAGONAL(1, -1);

    private final int dx;
    private final int dy;

    Axis(int dx, int dy) {
        this.dx = dx;
        this.dy = dy;
    }

    public int dx() {
        return dx;
    }

    public int dy() {
        return dy;
    }
}
