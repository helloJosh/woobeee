package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public class RoomService {
    private static final int MIN_PLAYERS = 2;

    private final RoomRegistry roomRegistry;
    private final Map<String, Set<String>> recentlyDisconnected = new HashMap<>();

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
     */
    public Room join(String roomId, String inviteCode, GameParticipant participant) {
        Room room = requireRoom(roomId, inviteCode);

        if (room.member(participant.participantId()).isPresent()) {
            room.setConnection(participant.participantId(), ConnectionState.CONNECTED);
            return room;
        }

        if (room.status() != RoomStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Game already started");
        }

        if (room.isFull()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Room is full");
        }

        room.addMember(participant);
        return room;
    }

    public Room setReady(String roomId, String participantId, boolean ready) {
        Room room = requireMember(roomId, participantId);
        room.setReady(participantId, ready);
        return room;
    }

    /** 소켓이 끊겼을 때. 자리는 남기고 연결만 끊긴 것으로 표시한다. */
    public void markDisconnected(String roomId, String participantId) {
        roomRegistry.find(roomId)
                .ifPresent(room -> {
                    room.setConnection(participantId, ConnectionState.DISCONNECTED);
                    recentlyDisconnected.computeIfAbsent(roomId, k -> new HashSet<>()).add(participantId);
                });
    }

    /**
     * 이탈 확정. 유예 만료 타이머와 명시적 LEAVE 가 같이 부른다.
     *
     * <p>유예 중에 재접속했다면 연결 상태가 CONNECTED 로 돌아와 있다. 그 경우 만료 타이머가
     * 뒤늦게 도착한 것이므로 아무것도 하지 않는다.
     */
    public void confirmLeave(String roomId, String participantId) {
        roomRegistry.find(roomId).ifPresent(room -> {
            Set<String> disconnected = recentlyDisconnected.getOrDefault(roomId, Set.of());
            boolean wasEverDisconnected = disconnected.contains(participantId);
            boolean isNowConnected = room.member(participantId)
                    .map(member -> member.connection() == ConnectionState.CONNECTED)
                    .orElse(false);

            // Only skip removal if this participant was disconnected before and is now reconnected.
            if (wasEverDisconnected && isNowConnected) {
                return;
            }

            removeAndSettle(room, participantId);
        });
    }

    /** 명시적 LEAVE. 유예 없이 즉시 뺀다. */
    public void leaveNow(String roomId, String participantId) {
        roomRegistry.find(roomId).ifPresent(room -> removeAndSettle(room, participantId));
    }

    public Room start(String roomId, String requesterParticipantId) {
        Room room = requireMember(roomId, requesterParticipantId);

        if (!room.hostParticipantId().equals(requesterParticipantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can start the game");
        }

        if (room.status() != RoomStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Game already started");
        }

        if (room.members().size() < MIN_PLAYERS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "At least two players are required");
        }

        if (room.gameType() == GameType.OMOK && room.members().size() != GameType.OMOK.capacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Omok requires exactly two players");
        }

        boolean allReady = room.members().stream().allMatch(RoomMember::ready);
        if (!allReady) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "All players must be ready");
        }

        room.setStatus(RoomStatus.IN_PROGRESS);
        return room;
    }

    private void removeAndSettle(Room room, String participantId) {
        room.removeMember(participantId);

        if (room.members().isEmpty()) {
            roomRegistry.remove(room.roomId());
            recentlyDisconnected.remove(room.roomId());
            return;
        }

        room.promoteNextHost();
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
