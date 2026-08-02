package com.woobeee.game.dodge;

import java.util.List;
import java.util.Map;

/**
 * 기보. 전체 상태가 아니라 <b>시드와 입력, 그리고 이탈만</b> 담는다.
 * 같은 시드로 같은 틱 로직을 다시 돌리면 원본이 재현되기 때문이다.
 *
 * <p>{@code departuresByTick} 이 별도로 필요한 이유: {@link DodgeGame#eliminate(String)} 은
 * {@code advanceOneTick} 이 읽는 입력 스트림 밖에서 게임 상태를 바꾼다 — 참가자가 방을 나가는
 * 것은 이동이 아니므로 {@code inputsByTick} 에는 흔적이 남지 않는다. 이탈로 끝난 게임(도중 이탈로
 * 한 명만 남는 것이 이 싱크의 주된 종료 경로다)의 기보에 그 이탈들을 싣지 않으면, 재생은 이탈한
 * 참가자를 계속 살려 둔 채 실제 장애물을 새로 굴리게 되어 원본과 다른 순위·다른 길이의 판이
 * 나온다. 그래서 이탈은 {@code inputsByTick} 과 나란히, "그 틱에 발생한" 이탈 참가자 목록으로
 * 따로 기록한다 — 같은 틱에 여러 명이 떠났다면 실제로 {@code eliminate} 가 불린 순서 그대로.
 */
public record DodgeReplay(
        int seed,
        List<String> participantIds,
        Map<Integer, Map<String, Direction>> inputsByTick,
        Map<Integer, List<String>> departuresByTick
) {
}
