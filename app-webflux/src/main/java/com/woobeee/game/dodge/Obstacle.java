package com.woobeee.game.dodge;

/**
 * 낙하 블록. v3 규칙부터 장애물은 1칸이 아니라 서브칸 단위의 {@code w}×{@code h} 박스다.
 * {@code (x, y)} 는 왼쪽 위 서브칸이다.
 */
public record Obstacle(int x, int y, int w, int h) {

    /** 이 블록이 [{@code left}..{@code right}]×[{@code top}..{@code bottom}] 박스와 겹치는가. */
    public boolean overlaps(int left, int top, int right, int bottom) {
        return left <= x + w - 1 && x <= right && top <= y + h - 1 && y <= bottom;
    }
}
