package com.woobeee.game.dodge;

import java.util.ArrayList;
import java.util.List;

/**
 * 격자와 난이도 곡선. 기보 재생이 서버와 같은 결과를 내야 하므로 이 상수들이 곧 계약이다 —
 * 기보 헤더에 함께 실어 보낸다.
 */
public final class DodgeRules {
    public static final int COLUMNS = 12;
    public static final int ROWS = 16;
    public static final int TICK_MILLIS = 100;

    public static final double BASE_SPAWN = 0.15;
    public static final double SPAWN_STEP = 0.05;
    public static final int SPAWN_STEP_TICKS = 100;
    public static final double MAX_SPAWN = 0.60;

    private DodgeRules() {
    }

    /** 벽시계가 아니라 틱 수로 난이도를 올린다 — 루프를 순수하게 유지하기 위해서다. */
    public static double spawnProbability(int tick) {
        double raised = BASE_SPAWN + SPAWN_STEP * (tick / SPAWN_STEP_TICKS);
        return Math.min(raised, MAX_SPAWN);
    }

    /** 최하단 행에 균등 간격으로 배치한다. */
    public static List<Cell> startingCells(int playerCount) {
        List<Cell> cells = new ArrayList<>(playerCount);
        int y = ROWS - 1;

        for (int i = 0; i < playerCount; i++) {
            int x = (int) Math.round((i + 0.5) * COLUMNS / (double) playerCount - 0.5);
            cells.add(new Cell(Math.clamp(x, 0, COLUMNS - 1), y));
        }
        return cells;
    }
}
