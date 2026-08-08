package com.woobeee.game.room;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.identity.GameParticipant;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class RoomService {
    private final RoomRegistry roomRegistry;

    public RoomService(RoomRegistry roomRegistry) {
        this.roomRegistry = roomRegistry;
    }

    public Room create(GameType gameType, GameParticipant host) {
        return roomRegistry.create(gameType, host);
    }

    public Room requireRoom(String roomId, String inviteCode) {
        Room room = roomRegistry.find(roomId)
                .orElseThrow(GameErrorCode.ROOM_NOT_FOUND::asException);

        if (!room.inviteCode().equals(inviteCode)) {
            throw GameErrorCode.INVALID_INVITE_CODE.asException();
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
     *
     * <p>둘을 {@code Room} 하나로 뭉뚱그리지 않고 {@link JoinOutcome} 으로 구분해 돌려준다 —
     * 진행 중인 게임에 다시 붙은 참가자에게는 화면을 다시 그릴 스냅샷을 보내야 하는데, 최초
     * 참가와 재접속이 같은 값으로 돌아오면 호출자가 그 둘을 다시 알아낼 방법이 없다(연결 상태를
     * 읽어 봐야 이미 CONNECTED 로 되돌아간 뒤다).
     */
    public JoinOutcome join(String roomId, String inviteCode, GameParticipant participant) {
        Room room = requireRoom(roomId, inviteCode);

        Room.AdmitResult result = room.admit(participant, RoomStatus.WAITING);
        return switch (result) {
            case RECONNECTED -> new JoinOutcome(room, true);
            case ADMITTED -> new JoinOutcome(room, false);
            case GAME_ALREADY_STARTED -> throw GameErrorCode.GAME_ALREADY_STARTED.asException();
            case ROOM_FULL -> throw GameErrorCode.ROOM_FULL.asException();
        };
    }

    /**
     * {@link #join} 의 결과.
     *
     * @param room        참가가 확정된 방
     * @param reconnected 이미 그 방의 멤버였고 연결만 되살아난 것이면 true, 새로 자리를 받았으면 false
     */
    public record JoinOutcome(Room room, boolean reconnected) {
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

        Room.StartResult result = room.beginGame(requesterParticipantId, room.gameType().minPlayersToStart());
        return switch (result) {
            case STARTED -> room;
            case NOT_HOST -> throw GameErrorCode.NOT_HOST.asException();
            case NOT_WAITING -> throw GameErrorCode.GAME_ALREADY_STARTED.asException();
            case NOT_ENOUGH_PLAYERS -> throw GameErrorCode.NOT_ENOUGH_PLAYERS.asException();
            case OMOK_REQUIRES_TWO -> throw GameErrorCode.OMOK_REQUIRES_TWO.asException();
            case NOT_ALL_READY -> throw GameErrorCode.NOT_ALL_READY.asException();
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
                .orElseThrow(GameErrorCode.ROOM_NOT_FOUND::asException);
    }

    /**
     * 재대국(GAME-AC-30). 방의 아무 멤버나 걸 수 있다 — 어차피 전원이 다시 READY 를 눌러야
     * 시작되므로 방장만으로 좁힐 이유가 없다. FINISHED 가 아니면 거절한다.
     */
    public Room rematch(String roomId, String participantId) {
        Room room = requireMember(roomId, participantId);

        if (!room.rearmForRematch()) {
            throw GameErrorCode.REMATCH_NOT_FINISHED.asException();
        }
        return room;
    }

    private Room requireMember(String roomId, String participantId) {
        Room room = roomRegistry.find(roomId)
                .orElseThrow(GameErrorCode.ROOM_NOT_FOUND::asException);

        if (room.member(participantId).isEmpty()) {
            throw GameErrorCode.NOT_A_MEMBER.asException();
        }

        return room;
    }
}
