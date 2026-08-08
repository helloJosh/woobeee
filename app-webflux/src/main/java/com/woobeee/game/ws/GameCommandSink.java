package com.woobeee.game.ws;

import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;

/**
 * 게임별 로직이 붙는 확장점. Plan 2(오목)와 Plan 3(장애물피하기)이 각각 구현한다.
 *
 * <p>모든 메서드는 {@link RoomCommandDispatcher} 가 호출한다. 방 명령을 직렬화하는 큐는 없다 —
 * 여러 세션이 같은 방에 동시에 명령을 보내면 이 인터페이스의 메서드도 서로 다른 스레드에서 동시에
 * 불릴 수 있다. 인자로 받는 {@link Room} 은 그 자체로 스레드 안전하므로(멤버 조회/변경이 손상되지
 * 않는다) 방 상태를 읽고 쓰는 것은 안전하지만, 구현체가 room 밖에 자기만의 상태(예: 게임판)를
 * 갖는다면 그 상태의 동시 접근은 구현체가 직접 책임져야 한다.
 */
public interface GameCommandSink {
    GameType gameType();

    void onStart(Room room);

    void onGameCommand(Room room, String participantId, ClientMessage message);

    /**
     * 이미 진행 중인 게임에 참가자가 다시 붙었을 때 불린다. 구현체는 화면을 다시 그릴 수 있을 만큼의
     * 상태를 GAME_SNAPSHOT 으로 내보낸다. 진행 중인 게임이 없으면 아무것도 하지 않는다.
     *
     * <p>기본 구현을 두지 않는다 — 조용히 아무것도 안 하는 default 야말로 다음 게임 타입이 이
     * 버그를 그대로 다시 출시하는 방법이다.
     */
    void onRejoin(Room room, String participantId);

    void onParticipantGone(Room room, String participantId);
}
