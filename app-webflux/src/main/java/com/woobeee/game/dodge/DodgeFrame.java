package com.woobeee.game.dodge;

import java.util.List;
import java.util.Map;

public record DodgeFrame(
        int tick,
        Map<String, Cell> positions,
        List<Cell> obstacles,
        List<String> eliminatedThisTick,
        boolean finished
) {
}
