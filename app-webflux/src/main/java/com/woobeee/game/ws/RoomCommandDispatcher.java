package com.woobeee.game.ws;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.room.RoomStatus;
import com.woobeee.game.ws.payload.ErrorPayload;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 방 상태를 바꾸는 유일한 진입점. 실패를 예외로 던지지 않고 ERROR 메시지로 흘려보내,
 * 소켓 하나의 잘못된 입력이 방 전체를 끊지 않게 한다.
 */
@Component
public class RoomCommandDispatcher {
    private final RoomService roomService;
    private final RoomHub roomHub;
    private final Map<GameType, GameCommandSink> sinks = new EnumMap<>(GameType.class);

    public RoomCommandDispatcher(RoomService roomService, RoomHub roomHub, List<GameCommandSink> gameCommandSinks) {
        this.roomService = roomService;
        this.roomHub = roomHub;
        gameCommandSinks.forEach(sink -> this.sinks.put(sink.gameType(), sink));
    }

    /**
     * roomId/inviteCode 3인자 버전. 검증이 끝난 뒤 별도로 할 일이 없는 호출자(테스트 등)를 위한
     * 편의 오버로드다.
     */
    public Optional<GameErrorCode> join(String roomId, String inviteCode, GameParticipant participant) {
        return join(roomId, inviteCode, participant, () -> { });
    }

    /**
     * 방 참가를 검증하고 확정한다. 성공이면 빈 {@link Optional}, 거절이면 그 이유를 돌려준다 —
     * {@link GameWebSocketHandler} 는 이 값을 보고서야 세션을 "참가함" 상태로 만들어야 한다(C2).
     * 검증(초대 코드, 정원, 진행 상태) 전에 세션을 허브에 구독시키면, 유효한 토큰이지만 틀린 초대
     * 코드를 댄 참가자가 다른 참가자의 ROOM_STATE 를 계속 엿듣게 된다.
     *
     * <p><b>거절을 방에 브로드캐스트하지 않는다</b> — 다른 명령들과 다른 점이다. 거절당한 사람은
     * 아직 이 방 사람이 아니므로 그의 실패는 방의 일이 아니고, 무엇보다 그 세션은 아직 허브를
     * 구독하지 않았으므로 브로드캐스트는 정작 당사자에게 닿지 않는다 — 남들만 남의 실패를
     * 받아 보게 된다. 그래서 이유를 호출자에게 돌려주고, 호출자가 그 세션에 직접 써 준다
     * ({@code GameWebSocketHandler.rejectWithReason}).
     *
     * <p><b>"거절"은 입장 판정이 실패한 것만을 말한다.</b> {@code roomService.join} 이 통과한
     * 뒤에 나는 실패(ROOM_STATE 브로드캐스트, {@code onRejoin})는 거절이 아니라 이미 방에 들어온
     * 참가자에게 생긴 사고다. 그 시점엔 {@code onValidated} 가 이미 돌아 세션이 허브를 구독했고
     * outbound 스트림이 살아 있으므로, 그런 실패까지 거절로 돌려주면 호출자가 살아 있는 소켓에
     * 두 번째 writer 를 붙이게 된다. 그래서 그 경우는 빈 Optional 을 돌려주고 오류는 방으로
     * 흘려보낸다 — 당사자는 이미 구독 중이니 그 편이 닿는다.
     *
     * <p>{@code onValidated} 는 참가가 실제로 확정된 직후, 이 참가에 대한 ROOM_STATE 를 방에
     * 브로드캐스트하기 직전에 불린다. 호출자는 여기서 허브 구독을 열어 자신의 참가로 인한
     * ROOM_STATE 를 놓치지 않게 한다.
     *
     * <p>진행 중인 게임에 <b>다시</b> 붙은 참가자에게는 ROOM_STATE 만으로 충분하지 않다 — 참가자
     * 목록과 방 상태만으로는 판도 틱도 복원할 수 없어서, 30초 유예 안에 돌아온 플레이어가 자리는
     * 지킨 채 빈 화면만 보게 된다. 그래서 ROOM_STATE 뒤에 싱크의 {@code onRejoin} 을 불러
     * GAME_SNAPSHOT 을 내보낸다. 최초 참가는 따라잡을 상태가 없으므로 부르지 않는다.
     */
    public Optional<GameErrorCode> join(
            String roomId,
            String inviteCode,
            GameParticipant participant,
            Runnable onValidated
    ) {
        AtomicBoolean admitted = new AtomicBoolean(false);

        Optional<GameErrorCode> failure = attempt(() -> {
            RoomService.JoinOutcome outcome = roomService.join(roomId, inviteCode, participant);
            // 입장 판정은 여기서 끝났다. 이 뒤의 실패는 더 이상 거절이 아니다.
            admitted.set(true);

            Room room = outcome.room();
            onValidated.run();
            broadcastRoomState(room);

            if (outcome.reconnected() && room.status() == RoomStatus.IN_PROGRESS) {
                Optional.ofNullable(sinks.get(room.gameType()))
                        .ifPresent(sink -> sink.onRejoin(room, participant.participantId()));
            }
        });

        if (failure.isPresent() && admitted.get()) {
            roomHub.broadcast(roomId, ServerMessage.of("ERROR", ErrorPayload.of(failure.get())));
            return Optional.empty();
        }
        return failure;
    }

    /**
     * 실패는 {@code caller} 에게만 간다. 준비 토글이 거절되는 이유(방이 없다, 이 방의 멤버가
     * 아니다)는 전부 그 사람 사정이고, 방 사람들이 알아야 할 일은 성공했을 때의 ROOM_STATE 뿐이다.
     */
    public void ready(String roomId, String participantId, boolean ready, SessionChannel caller) {
        guard(caller, null, () -> broadcastRoomState(roomService.setReady(roomId, participantId, ready)));
    }

    /**
     * 재대국(GAME-AC-30). 성공하면 WAITING 으로 돌아간 ROOM_STATE 가 방 전체에 나가고,
     * 실패(방이 아직 FINISHED 가 아니다, 멤버가 아니다)는 준비 토글과 같은 이유로 누른
     * 사람에게만 간다.
     */
    public void rematch(String roomId, String participantId, SessionChannel caller) {
        guard(caller, null, () -> broadcastRoomState(roomService.rematch(roomId, participantId)));
    }

    /**
     * 시작 시도. <b>실패의 목적지가 두 곳</b>이라는 점이 다른 명령과 다르다 — {@link #join} 이
     * 입장 확정 전후를 가르는 것과 같은 이유다.
     *
     * <p>{@code roomService.start} 가 던진 것(방장이 아니다, 인원이 모자란다, 아직 준비가 안
     * 됐다)은 방 상태를 하나도 건드리지 않은 채 거절된 것이므로 누른 사람에게만 간다. 예전에는
     * 이것이 방 전체로 나가, 방장이 아닌 사람이 START 를 누르면 여덟 명 화면에 전부
     * "방장만 게임을 시작할 수 있습니다" 가 떴다.
     *
     * <p>그러나 {@code start} 가 통과한 뒤의 실패는 다르다. 그 시점에 방은 이미 IN_PROGRESS 로
     * 넘어갔고 그 사실이 ROOM_STATE 로 방에 나갔다 — 그다음 {@code sink.onStart} 나 GAME_START
     * 방송이 실패하면 <b>모두가</b> "시작됐다고 들었는데 게임이 오지 않는" 상태에 놓인다.
     * 그건 진짜로 방의 소식이므로 허브로 보낸다.
     */
    public void start(String roomId, String participantId, SessionChannel caller) {
        AtomicBoolean started = new AtomicBoolean(false);

        Optional<GameErrorCode> failure = attempt(() -> {
            Room room = roomService.start(roomId, participantId);
            // 상태 전환은 여기서 끝났다. 이 뒤의 실패는 더 이상 "네 START 가 거절됐다" 가 아니다.
            started.set(true);

            broadcastRoomState(room);
            Optional.ofNullable(sinks.get(room.gameType())).ifPresent(sink -> sink.onStart(room));
            roomHub.broadcast(roomId, ServerMessage.of("GAME_START", Map.of("roomId", roomId)));
        });

        failure.ifPresent(errorCode -> {
            ServerMessage message = ServerMessage.of("ERROR", ErrorPayload.of(errorCode));
            if (started.get()) {
                roomHub.broadcast(roomId, message);
            } else {
                caller.send(message);
            }
        });
    }

    /**
     * C2: {@code requireRoomById} 는 방 존재만 확인하고 멤버십은 보지 않는다 — 그래서 초대
     * 코드가 틀려 join 이 실패한 세션이라도(혹은 애초에 이 방에 들어온 적 없는 세션이라도)
     * roomId 만 알면 게임 명령을 sink 까지 흘려보낼 수 있었다. 여기서 먼저 멤버십을 확인한다.
     */
    public void gameCommand(String roomId, String participantId, ClientMessage message, SessionChannel caller) {
        guard(caller, message.seq(), () -> {
            Room room = roomService.requireRoomById(roomId);
            if (room.member(participantId).isEmpty()) {
                throw GameErrorCode.NOT_A_MEMBER.asException();
            }
            GameCommandSink sink = sinks.get(room.gameType());
            if (sink == null) {
                throw new IllegalStateException("No game handler for " + room.gameType());
            }
            sink.onGameCommand(room, participantId, message);
        });
    }

    public void disconnected(String roomId, String participantId) {
        roomService.markDisconnected(roomId, participantId);
        roomService.findRoom(roomId).ifPresent(this::broadcastRoomState);
    }

    /**
     * 이탈 정리의 실패는 방으로 간다.
     *
     * <p>이유는 <b>목적지가 없어서가 아니라 내용 때문</b>이다. {@link #settle} 이 실패했다는
     * 것은 방의 명단·방장·게임 상태가 어긋났을 수 있다는 뜻이고, 그건 남은 사람들이 알아야 할
     * 일이다 — 떠나는 당사자에게 알려 봐야 아무 소용이 없다.
     *
     * <p>{@code confirmLeave} 는 거기에 더해 알릴 세션 자체가 없다. 유예 만료 타이머가 부르는
     * 시점에 그 소켓은 이미 사라진 뒤다.
     */
    public void confirmLeave(String roomId, String participantId) {
        guard(roomHubChannel(roomId), null,
                () -> settle(roomId, participantId, () -> roomService.confirmLeave(roomId, participantId)));
    }

    /**
     * {@link #confirmLeave} 와 같은 이유로 방으로 보낸다 — <b>다만 목적지가 없어서는 아니다</b>.
     * 이쪽은 {@code GameWebSocketHandler} 가 살아 있는 세션에서 부르므로 {@code caller} 를
     * 넘길 수 있었다. 넘기지 않는 것은 선택이다: 이 실패가 말하는 것은 "네 LEAVE 가 거절됐다"
     * 가 아니라 "이 방의 상태가 어긋났다" 이고, 그 소식이 필요한 쪽은 <b>남는 사람들</b>이다.
     * 떠나는 세션은 프레임을 읽기도 전에 닫힌다.
     */
    public void leaveNow(String roomId, String participantId) {
        guard(roomHubChannel(roomId), null,
                () -> settle(roomId, participantId, () -> roomService.leaveNow(roomId, participantId)));
    }

    /**
     * 참가자 이탈 뒤처리. 방이 없어졌든(마지막 멤버) 아니든 실제로 자리를 비운 것이면
     * 싱크에 반드시 알린다 — 싱크는 방 id로 게임 상태를 들고 있다가 이 신호로 정리하므로,
     * 마지막 이탈에서 이걸 건너뛰면 그 게임 상태가 영영 안 지워진다.
     *
     * <p>싱크 통지는 허브를 닫기 전에 한다(마지막 메시지를 보낼 기회를 준다), 하지만 싱크가
     * 던지더라도 허브는 finally 에서 반드시 닫는다 — 그래야 이 경로가 실패해도 방 하나가
     * 영원히 sink/버퍼를 붙든 채로 새지 않는다. 예외는 그대로 다시 던져 guard가 ERROR로
     * 바꿔 내보내게 한다.
     */
    private void settle(String roomId, String participantId, Runnable removal) {
        Optional<Room> before = roomService.findRoom(roomId);
        boolean wasMember = before.flatMap(room -> room.member(participantId)).isPresent();

        removal.run();

        Optional<Room> after = roomService.findRoom(roomId);
        boolean participantGone = wasMember
                && after.map(room -> room.member(participantId).isEmpty()).orElse(true);

        try {
            if (participantGone) {
                Room departedFrom = after.orElseGet(before::get);
                Optional.ofNullable(sinks.get(departedFrom.gameType()))
                        .ifPresent(sink -> sink.onParticipantGone(departedFrom, participantId));
            }
        } finally {
            if (after.isEmpty()) {
                roomHub.close(roomId);
            }
        }

        after.ifPresent(this::broadcastRoomState);
    }

    private void broadcastRoomState(Room room) {
        roomHub.broadcast(room.roomId(), ServerMessage.of("ROOM_STATE", RoomStateProjector.project(room)));
    }

    /**
     * action 을 돌리고 실패하면 그 이유를 코드로 돌려준다. 아무 데도 알리지 않는다 — 알릴 곳을
     * 고르는 것은 호출자의 몫이다. 방에 알려야 하는 명령은 {@link #guard}, 거절당한 세션에게만
     * 알려야 하는 참가는 {@link #join} 이 쓴다.
     *
     * @return 성공이면 빈 Optional, 실패면 그 이유
     */
    private Optional<GameErrorCode> attempt(Runnable action) {
        try {
            action.run();
            return Optional.empty();
        } catch (ResponseStatusException exception) {
            return Optional.of(GameErrorCode.of(exception));
        } catch (RuntimeException exception) {
            // 예외 메시지는 밖으로 내보내지 않는다 — HTTP catch-all 과 같은 규칙이다.
            return Optional.of(GameErrorCode.UNEXPECTED);
        }
    }

    /**
     * action 을 돌리고, 실패하면 그 이유를 ERROR 로 {@code destination} 에 흘려보낸다.
     *
     * <p>목적지를 인자로 받는 것이 핵심이다. 예전에는 언제나 방 허브였고, 그래서 한 사람의
     * 실패가 방 전체의 배너가 됐다.
     */
    private void guard(SessionChannel destination, Long ackSeq, Runnable action) {
        attempt(action).ifPresent(errorCode ->
                destination.send(ServerMessage.ack("ERROR", ackSeq, ErrorPayload.of(errorCode))));
    }

    /** 방 전체가 목적지인 경우. 알릴 세션이 없는 이탈 정리 경로만 쓴다. */
    private SessionChannel roomHubChannel(String roomId) {
        return message -> roomHub.broadcast(roomId, message);
    }
}
