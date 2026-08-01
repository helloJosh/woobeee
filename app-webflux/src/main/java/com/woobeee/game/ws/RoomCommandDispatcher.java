package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomService;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public void join(String roomId, String inviteCode, GameParticipant participant) {
        guard(roomId, null, () -> {
            Room room = roomService.join(roomId, inviteCode, participant);
            broadcastRoomState(room);
        });
    }

    public void ready(String roomId, String participantId, boolean ready) {
        guard(roomId, null, () -> broadcastRoomState(roomService.setReady(roomId, participantId, ready)));
    }

    public void start(String roomId, String participantId) {
        guard(roomId, null, () -> {
            Room room = roomService.start(roomId, participantId);
            broadcastRoomState(room);
            Optional.ofNullable(sinks.get(room.gameType())).ifPresent(sink -> sink.onStart(room));
            roomHub.broadcast(roomId, ServerMessage.of("GAME_START", Map.of("roomId", roomId)));
        });
    }

    public void gameCommand(String roomId, String participantId, ClientMessage message) {
        guard(roomId, message.seq(), () -> {
            Room room = roomService.requireRoomById(roomId);
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

    public void confirmLeave(String roomId, String participantId) {
        guard(roomId, null, () -> settle(roomId, participantId, () -> roomService.confirmLeave(roomId, participantId)));
    }

    public void leaveNow(String roomId, String participantId) {
        guard(roomId, null, () -> settle(roomId, participantId, () -> roomService.leaveNow(roomId, participantId)));
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

    private void guard(String roomId, Long ackSeq, Runnable action) {
        try {
            action.run();
        } catch (ResponseStatusException exception) {
            roomHub.broadcast(roomId, ServerMessage.ack("ERROR", ackSeq, Map.of(
                    "code", exception.getStatusCode().value(),
                    "message", String.valueOf(exception.getReason())
            )));
        } catch (RuntimeException exception) {
            roomHub.broadcast(roomId, ServerMessage.ack("ERROR", ackSeq, Map.of(
                    "code", 500,
                    "message", "Command failed"
            )));
        }
    }
}
