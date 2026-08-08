package com.woobeee.game.dodge;

import java.util.ArrayList;
import java.util.List;

/**
 * 격자와 난이도 곡선. 기보 재생이 서버와 같은 결과를 내야 하므로 이 상수들이 곧 계약이다 —
 * 기보 헤더에 함께 실어 보낸다.
 *
 * <p><b>v3 규칙.</b> 옛 "이동 칸" 하나를 3×3 서브칸으로 쪼갰다. 플레이어는 3×3 서브칸 박스이고
 * 입력 1회에 3서브칸(=옛 1칸)씩 움직인다 — 체감 이동은 v2 와 같다. 장애물은 서브칸 단위의
 * 가변 크기 블록({@link Obstacle})이고 틱당 1서브칸씩 떨어진다(옛 속도의 1/3). 화면에 머무는
 * 시간이 3배가 됐으므로 스폰 확률 곡선을 1/3 스케일로 내려 밀도를 비슷하게 맞췄다.
 */
public final class DodgeRules {
    /** 옛 "이동 칸" 하나가 몇 서브칸인가. 이동량·플레이어 크기·스폰 슬롯 폭이 전부 이 값이다. */
    public static final int SUBCELLS_PER_CELL = 3;

    public static final int COLUMNS = 36;
    public static final int ROWS = 48;
    /** 플레이어 박스 한 변(서브칸). 좌표는 왼쪽 위 서브칸이다. */
    public static final int PLAYER_SIZE = SUBCELLS_PER_CELL;
    /** 입력 1회의 이동량(서브칸). */
    public static final int MOVE_STEP = SUBCELLS_PER_CELL;
    /** 틱당 스폰 굴림 수. 슬롯 i 는 서브칸 x = i*3 에서 시작한다. */
    public static final int SPAWN_SLOTS = COLUMNS / SUBCELLS_PER_CELL;
    public static final int TICK_MILLIS = 100;

    public static final double BASE_SPAWN = 0.01;
    public static final double SPAWN_STEP = 0.01;
    public static final int SPAWN_STEP_TICKS = 100;
    public static final double MAX_SPAWN = 0.15;

    /** 낙하 속도(서브칸/틱). 300틱(30초)마다 1씩 올라 최대 3이 된다 — 후반의 압박은 밀도와 속도 둘 다다. */
    public static final int FALL_SPEED_STEP_TICKS = 300;
    public static final int MAX_FALL_SPEED = 3;

    public static final int MIN_OBSTACLE_WIDTH = 2;
    public static final int MAX_OBSTACLE_WIDTH = 5;
    public static final int MIN_OBSTACLE_HEIGHT = 2;
    public static final int MAX_OBSTACLE_HEIGHT = 3;

    private DodgeRules() {
    }

    /** 벽시계가 아니라 틱 수로 난이도를 올린다 — 루프를 순수하게 유지하기 위해서다. */
    public static double spawnProbability(int tick) {
        double raised = BASE_SPAWN + SPAWN_STEP * (tick / SPAWN_STEP_TICKS);
        return Math.min(raised, MAX_SPAWN);
    }

    /**
     * 이 틱의 낙하량(서브칸). {@link #MAX_FALL_SPEED} 는
     * {@code PLAYER_SIZE + MIN_OBSTACLE_HEIGHT} 보다 작아야 한다 — 그보다 크면 가만히 서 있는
     * 플레이어를 블록이 한 틱에 통째로 건너뛴다(DodgeGame 의 충돌 주석 참조).
     */
    public static int fallSpeed(int tick) {
        return Math.min(1 + tick / FALL_SPEED_STEP_TICKS, MAX_FALL_SPEED);
    }

    /**
     * 최하단에 균등 간격으로 배치한다. 옛 공식(12칸 공간의
     * {@code round((i + 0.5) * 12 / playerCount - 0.5)})을 슬롯 공간에서 그대로 쓰고
     * ×3 해 서브칸 좌표로 만든다 — 배치 모양이 v2 와 같게 유지된다.
     */
    public static List<Cell> startingCells(int playerCount) {
        List<Cell> cells = new ArrayList<>(playerCount);
        int y = ROWS - PLAYER_SIZE;

        for (int i = 0; i < playerCount; i++) {
            int slot = (int) Math.round((i + 0.5) * SPAWN_SLOTS / (double) playerCount - 0.5);
            int x = Math.clamp(slot, 0, SPAWN_SLOTS - 1) * SUBCELLS_PER_CELL;
            cells.add(new Cell(x, y));
        }
        return cells;
    }
}
