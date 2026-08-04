package com.woobeee.game.dodge;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DodgeGame} 의 패키지 전용 시나리오 생성자에 접근하는 테스트 헬퍼.
 *
 * <p>같은 패키지라 접근 가능하다. {@code DodgeGame} 자체에는 테스트 전용 메서드를 두지 않는다 —
 * 대신 위치·장애물·스폰 확률을 생성자 인자로 주입해서 틱 로직을 격리해 본다.
 */
final class DodgeGameTestSupport {

    private DodgeGameTestSupport() {
    }

    /** 장애물이 절대 생성되지 않는 게임 — 기본 시작 위치를 그대로 쓴다. */
    static DodgeGame quiet(int seed, String... players) {
        return quiet(seed, Map.of(), List.of(), players);
    }

    /** 장애물이 절대 생성되지 않는 게임 — 초기 장애물만 지정한다. */
    static DodgeGame quiet(int seed, List<Obstacle> obstacles, String... players) {
        return quiet(seed, Map.of(), obstacles, players);
    }

    /** 장애물이 절대 생성되지 않는 게임 — 참가자 위치와 장애물을 모두 지정한다. */
    static DodgeGame quiet(int seed, Map<String, Cell> positionOverrides, List<Obstacle> obstacles, String... players) {
        return quietAtTick(seed, 0, positionOverrides, obstacles, players);
    }

    /** 위와 같되 시작 틱을 지정한다 — 낙하 속도가 올라간 구간을 300틱을 돌리지 않고 본다. */
    static DodgeGame quietAtTick(
            int seed, int startingTick, Map<String, Cell> positionOverrides, List<Obstacle> obstacles, String... players) {
        List<Cell> defaults = DodgeRules.startingCells(players.length);
        Map<String, Cell> positions = new LinkedHashMap<>();
        for (int i = 0; i < players.length; i++) {
            positions.put(players[i], positionOverrides.getOrDefault(players[i], defaults.get(i)));
        }
        return new DodgeGame(List.of(players), seed, positions, obstacles, 0.0, startingTick);
    }
}
