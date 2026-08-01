package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class RoomService {
    private static final int MIN_PLAYERS = 2;

    private final RoomRegistry roomRegistry;

    public RoomService(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    public Room create(GameType gameType, GameParticipant host) {
        return roomRegistry.create(gameType, host);
    }

    public Room requireRoom(String roomId, String inviteCode) {
        Room room = roomRegistry.find(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        if (!room.inviteCode().equals(inviteCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid invite code");
        }

        return room;
    }

    /**
     * 최초 참가와 재접속을 같은 진입점으로 다룬다. 이미 방에 있는 participantId 면 재접속이므로
     * 정원과 진행 상태 검사를 건너뛰고 연결 상태만 되돌린다.
     *
     * <p>판정과 추가는 {@link Room#admit(GameParticipant, RoomStatus)} 하나로 원자적으로
     * 수행한다 — 검사와 추가를 여기서 별도의 {@code Room} 호출로 나누면 그 사이에
     * {@link #start} 가 끼어들어 상태를 바꿀 수 있다(체크-후-액션 경합).
     */
    public Room join(String roomId, String inviteCode, GameParticipant participant) {
        Room room = requireRoom(roomId, inviteCode);

        Room.AdmitResult result = room.admit(participant, RoomStatus.WAITING);
        return switch (result) {
            case RECONNECTED, ADMITTED -> room;
            case GAME_ALREADY_STARTED -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Game already started");
            case ROOM_FULL -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Room is full");
        };
    }

    public Room setReady(String roomId, String participantId, boolean ready) {
        Room room = requireMember(roomId, participantId);
        room.setReady(participantId, ready);
        return room;
    }

    /** 소켓이 끊겼을 때. 자리는 남기고 연결만 끊긴 것으로 표시한다. */
    public void markDisconnected(String roomId, String participantId) {
        roomRegistry.find(roomId)
                .ifPresent(room -> room.setConnection(participantId, ConnectionState.DISCONNECTED));
    }

    /**
     * 이탈 확정. 유예 만료 타이머가 부른다.
     *
     * <p>유예 중에 재접속했다면 연결 상태가 CONNECTED 로 돌아와 있다. 그 경우 만료 타이머가
     * 뒤늦게 도착한 것이므로 아무것도 하지 않는다.
     */
    public void confirmLeave(String roomId, String participantId) {
        roomRegistry.find(roomId).ifPresent(room -> {
            boolean reconnected = room.member(participantId)
                    .map(member -> member.connection() == ConnectionState.CONNECTED)
                    .orElse(false);
            if (reconnected) {
                return;
            }

            removeAndSettle(room, participantId);
        });
    }

    /** 명시적 LEAVE. 유예 없이 즉시 뺀다. */
    public void leaveNow(String roomId, String participantId) {
        roomRegistry.find(roomId).ifPresent(room -> removeAndSettle(room, participantId));
    }

    /**
     * 방장 확인부터 상태 전환까지를 {@link Room#beginGame(String, int)} 하나로 원자적으로
     * 수행한다 — 검사들을 여기서 여러 번의 {@code Room} 호출로 나누면 그 사이에 {@link #join}
     * 이 끼어들어 인원을 바꿀 수 있다(체크-후-액션 경합).
     */
    public Room start(String roomId, String requesterParticipantId) {
        Room room = requireMember(roomId, requesterParticipantId);

        Room.StartResult result = room.beginGame(requesterParticipantId, MIN_PLAYERS);
        return switch (result) {
            case STARTED -> room;
            case NOT_HOST -> throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can start the game");
            case NOT_WAITING -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Game already started");
            case NOT_ENOUGH_PLAYERS -> throw new ResponseStatusException(HttpStatus.CONFLICT, "At least two players are required");
            case OMOK_REQUIRES_TWO -> throw new ResponseStatusException(HttpStatus.CONFLICT, "Omok requires exactly two players");
            case NOT_ALL_READY -> throw new ResponseStatusException(HttpStatus.CONFLICT, "All players must be ready");
        };
    }

    private void removeAndSettle(Room room, String participantId) {
        room.removeMember(participantId);

        if (room.members().isEmpty()) {
            roomRegistry.remove(room.roomId());
            return;
        }

        room.promoteNextHost();
    }

    public Optional<Room> findRoom(String roomId) {
        return roomRegistry.find(roomId);
    }

    public Room requireRoomById(String roomId) {
        return roomRegistry.find(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }

    private Room requireMember(String roomId, String participantId) {
        Room room = roomRegistry.find(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        if (room.member(participantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this room");
        }

        return room;
    }
}
