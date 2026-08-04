package com.woobeee.game.dodge;

import java.util.List;
import java.util.Map;

/**
 * 기보를 처음부터 다시 돌려 원본과 같은 결과를 재현한다.
 *
 * <p>같은 시드로 {@link DodgeGame} 을 새로 만들고, 기록된 입력만 틱 순서대로 적용한다.
 * 입력이 없는 틱은 빈 입력으로 진행한다 — 기보에 없는 틱은 "그 틱에 아무도 입력하지 않았다"는
 * 뜻이지 재생을 멈추라는 뜻이 아니다.
 *
 * <p>이탈은 그 틱의 입력을 적용하기 <b>전에</b> 먼저 반영한다 — 실제 게임에서도
 * {@link DodgeGame#eliminate(String)} 은 타이머가 그 틱을 돌리기 전, 틱 사이 어느 시점에든
 * 불릴 수 있는 별개의 사건이었다. 이탈이 게임을 끝냈다면(생존자 1명 이하) 그 틱의
 * {@code advanceOneTick} 은 원본에서도 실행되지 않았으므로(끝난 게임에서 호출은 아무 일도 하지
 * 않는 no-op이다) 여기서도 건너뛴다 — 그래야 틱 카운트가 원본과 정확히 같게 남는다.
 */
public class DodgeReplayRunner {
    /**
     * 손상된 기보가 무한 루프에 빠지지 않도록 하는 안전장치. 스폰 확률은 틱 1400에서 최댓값
     * (0.15)에 도달한 뒤 더 오르지 않고, 격자 높이(48서브행)상 장애물 하나가 바닥까지 내려오는 데
     * 최고 낙하 속도(3)로는 16틱이면 충분하다 — 규칙 자체에는 "정상 게임"의 길이를 못박는 상한이 없다(밀도가 1.0
     * 미만인 한 이론상 무한히 버틸 수 있다). 그래서 이 값은 게임 길이의 근거가 아니라, 100ms
     * 틱 기준 약 2.8시간에 달하는 순수한 안전 상한이다 — 실제 라운드가 이 근처에도 못 미치므로,
     * 도달하면 "긴 게임"이 아니라 종료하지 않는 기보라는 뜻이다.
     */
    private static final int MAX_TICKS = 100_000;

    private final int maxTicks;

    public DodgeReplayRunner() {
        this(MAX_TICKS);
    }

    /** 테스트 전용: 상한에 실제로 도달하는 경로를 십만 틱을 돌리지 않고 검증하기 위한 생성자. */
    DodgeReplayRunner(int maxTicks) {
        this.maxTicks = maxTicks;
    }

    public DodgeGame rerun(DodgeReplay replay) {
        DodgeGame game = new DodgeGame(replay.participantIds(), replay.seed());

        while (!game.finished() && game.tick() < maxTicks) {
            int currentTick = game.tick();

            for (String participantId : replay.departuresByTick().getOrDefault(currentTick, List.of())) {
                game.eliminate(participantId);
            }

            if (game.finished()) {
                break;
            }

            Map<String, Direction> inputs = replay.inputsByTick().getOrDefault(currentTick, Map.of());
            game.advanceOneTick(inputs);
        }

        if (!game.finished()) {
            throw new IllegalStateException(
                    "Dodge replay for seed " + replay.seed() + " did not finish within "
                            + maxTicks + " ticks — likely a malformed or non-terminating replay");
        }

        return game;
    }
}
