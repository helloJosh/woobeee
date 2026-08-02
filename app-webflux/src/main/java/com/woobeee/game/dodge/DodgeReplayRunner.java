package com.woobeee.game.dodge;

import java.util.Map;

/**
 * 기보를 처음부터 다시 돌려 원본과 같은 결과를 재현한다.
 *
 * <p>같은 시드로 {@link DodgeGame} 을 새로 만들고, 기록된 입력만 틱 순서대로 적용한다.
 * 입력이 없는 틱은 빈 입력으로 진행한다 — 기보에 없는 틱은 "그 틱에 아무도 입력하지 않았다"는
 * 뜻이지 재생을 멈추라는 뜻이 아니다.
 */
public class DodgeReplayRunner {
    /** 손상된 기보가 무한 루프에 빠지지 않도록 하는 안전장치. 정상 게임은 이 한계에 닿지 않는다. */
    private static final int MAX_TICKS = 100_000;

    public DodgeGame rerun(DodgeReplay replay) {
        DodgeGame game = new DodgeGame(replay.participantIds(), replay.seed());

        while (!game.finished() && game.tick() < MAX_TICKS) {
            Map<String, Direction> inputs =
                    replay.inputsByTick().getOrDefault(game.tick(), Map.of());
            game.advanceOneTick(inputs);
        }

        return game;
    }
}
