package com.woobeee.game.ws;

import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;

/**
 * 게임별 로직이 붙는 확장점. Plan 2(오목)와 Plan 3(장애물피하기)이 각각 구현한다.
 *
 * <p>모든 메서드는 방 명령 큐 안에서 호출되므로 같은 방에 대해 동시에 불리지 않는다.
 */
public interface GameCommandSink {
    GameType gameType();

    void onStart(Room room);

    void onGameCommand(Room room, String participantId, ClientMessage message);

    void onParticipantGone(Room room, String participantId);
}
