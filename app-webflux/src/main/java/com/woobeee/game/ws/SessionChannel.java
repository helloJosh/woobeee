package com.woobeee.game.ws;

/**
 * 명령을 낸 <b>그 세션 하나</b>에게만 가는 통로.
 *
 * <p>{@link RoomHub} 와의 차이가 요점이다. 허브는 방 전체가 받는 자리이고, 이것은 "네 명령이
 * 왜 실패했는지" 처럼 당사자 말고는 알 이유가 없는 것들의 자리다. 예전에는 그 구분이 없어
 * {@code RoomCommandDispatcher.guard} 가 실패를 전부 허브로 흘려보냈고, 방장이 아닌 사람이
 * START 를 눌러 실패하면 "방장만 게임을 시작할 수 있습니다" 가 여덟 명 화면에 전부 떴다.
 * {@code ackSeq} 로는 갈라낼 수 없다 — seq 는 클라이언트마다 1부터 세는 값이라 서로 겹친다.
 *
 * <p><b>구현은 세션에 직접 쓰지 않는다.</b> {@code GameWebSocketHandler} 는 세션마다
 * {@code Sinks.Many} 를 하나 두고, 그것을 허브 구독과 <b>합쳐</b> 하나의
 * {@code session.send(...)} 에 물린다. 참가 거절({@code rejectWithReason})은 아직 outbound
 * 스트림이 살아 있지 않은 시점이라 직접 써도 writer 가 하나뿐이지만, 여기는 다르다 — 이 통로가
 * 쓰이는 세 명령은 전부 참가가 확정된 뒤에만 도달하므로 outbound 는 이미 흐르는 중이고,
 * 직접 쓰면 같은 세션에 writer 가 둘이 된다. 합류시키는 쪽이 그 경합을 아예 없앤다.
 */
@FunctionalInterface
public interface SessionChannel {
    void send(ServerMessage message);
}
