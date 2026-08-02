package com.woobeee.game.dodge;

import java.util.List;
import java.util.Map;

/**
 * 기보. 전체 상태가 아니라 <b>시드와 입력만</b> 담는다.
 * 같은 시드로 같은 틱 로직을 다시 돌리면 원본이 재현되기 때문이다.
 */
public record DodgeReplay(
        int seed,
        List<String> participantIds,
        Map<Integer, Map<String, Direction>> inputsByTick
) {
}
