package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 방 하나의 인메모리 상태.
 *
 * <p>스레드 안전하다 — 모든 변경자와, 멤버 컬렉션을 순회하는 모든 조회자({@link #members()},
 * {@link #member(String)}, {@link #isFull()}, {@link #promoteNextHost()} 등)가 이 인스턴스를
 * 모니터로 동기화한다. 큐를 두어 방 명령을 직렬화하는 것이 아니라, 이 객체 자체가 동시 접근에
 * 안전한 것이다. {@code status} 와 {@code hostParticipantId} 의 가시성도 같은 동기화로
 * 보장된다.
 *
 * <p>정원 확인 후 추가, 진행 상태 확인 후 시작처럼 여러 단계를 조합하는 시퀀스는 각 단계를
 * 개별적으로 동기화하는 것만으로는 원자적이지 않다 — 그 사이에 다른 스레드가 끼어들 수 있다
 * (체크-후-액션 경합). 그래서 참가 판정은 {@link #admit(GameParticipant, RoomStatus)}, 시작
 * 판정은 {@link #beginGame(String, int)} 로 각각 하나의 동기화 블록에 모아 원자적으로 수행한다.
 * 호출자({@code RoomService})는 이 두 메서드가 돌려주는 결과를 상태 코드로 매핑하기만 한다.
 */
public final class Room {
    private final String roomId;
    private final String inviteCode;
    private final GameType gameType;
    private final Instant createdAt;
    private final Map<String, RoomMember> members = new LinkedHashMap<>();

    private String hostParticipantId;
    private RoomStatus status = RoomStatus.WAITING;

    public Room(String roomId, String inviteCode, GameType gameType, Instant createdAt, GameParticipant host) {
        this.roomId = roomId;
        this.inviteCode = inviteCode;
        this.gameType = gameType;
        this.createdAt = createdAt;
        this.hostParticipantId = host.participantId();
        this.members.put(host.participantId(), new RoomMember(host));
    }

    public String roomId() {
        return roomId;
    }

    public String inviteCode() {
        return inviteCode;
    }

    public GameType gameType() {
        return gameType;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public synchronized String hostParticipantId() {
        return hostParticipantId;
    }

    public synchronized RoomStatus status() {
        return status;
    }

    public synchronized List<RoomMember> members() {
        return List.copyOf(members.values());
    }

    public synchronized Optional<RoomMember> member(String participantId) {
        return Optional.ofNullable(members.get(participantId));
    }

    public synchronized boolean isFull() {
        return members.size() >= gameType.capacity();
    }

    public synchronized void addMember(GameParticipant participant) {
        members.put(participant.participantId(), new RoomMember(participant));
    }

    public synchronized void removeMember(String participantId) {
        members.remove(participantId);
    }

    public synchronized void setReady(String participantId, boolean ready) {
        member(participantId).ifPresent(m -> m.ready(ready));
    }

    public synchronized void setConnection(String participantId, ConnectionState connection) {
        member(participantId).ifPresent(m -> m.connection(connection));
    }

    public synchronized void setStatus(RoomStatus status) {
        this.status = status;
    }

    /** 방장이 빠진 뒤 참가 순서상 다음 사람을 방장으로 세운다. 아무도 없으면 그대로 둔다. */
    public synchronized void promoteNextHost() {
        if (members.containsKey(hostParticipantId)) {
            return;
        }
        members.keySet().stream().findFirst().ifPresent(next -> hostParticipantId = next);
    }

    /**
     * 참가 판정과 참가를 하나의 원자적 동작으로 묶는다.
     *
     * <p>이미 멤버라면(재접속) 정원·상태 검사를 건너뛰고 연결 상태만 CONNECTED 로 되돌린다.
     * 그 외에는 {@code expectedStatus} 와 다르면, 혹은 정원({@link GameType#capacity()})이
     * 찼으면 거절하고, 그렇지 않으면 멤버로 추가한다. 이 판정 전체가 하나의 동기화 블록 안에서
     * 이뤄지므로, 이 메서드를 호출하는 스레드와 {@link #beginGame(String, int)} 를 호출하는
     * 스레드는 서로의 시퀀스 중간에 끼어들 수 없다 — 둘 중 하나가 완전히 끝난 뒤에야 다른 하나가
     * 시작된다.
     */
    public synchronized AdmitResult admit(GameParticipant participant, RoomStatus expectedStatus) {
        RoomMember existing = members.get(participant.participantId());
        if (existing != null) {
            existing.connection(ConnectionState.CONNECTED);
            return AdmitResult.RECONNECTED;
        }

        AdmitResult preview = previewAdmission(expectedStatus);
        if (preview != AdmitResult.ADMITTED) {
            return preview;
        }

        members.put(participant.participantId(), new RoomMember(participant));
        return AdmitResult.ADMITTED;
    }

    /**
     * <b>신규</b> 진입자 관점에서 상태·정원만 미리 본다. 아무것도 바꾸지 않는다.
     *
     * <p>게스트 토큰 발급이 이걸 쓴다 — 들어갈 수 없는 방의 토큰을 만들어 두면 거절이
     * WebSocket JOIN 까지 밀리고, 그때는 이유를 보여줄 화면이 없다. 여기서 통과했다고 나중의
     * {@link #admit} 이 반드시 성공하는 것은 아니다(그 사이에 자리가 찰 수 있다). 진짜 판정은
     * 여전히 {@code admit} 이고, 이건 조기 거절용 예측일 뿐이다.
     *
     * <p>이미 멤버인 경우(재접속)는 여기서 다루지 않는다. 재접속은 {@code admit} 의
     * {@link AdmitResult#RECONNECTED} 경로이고 상태·정원 검사를 아예 건너뛴다.
     */
    public synchronized AdmitResult previewAdmission(RoomStatus expectedStatus) {
        if (status != expectedStatus) {
            return AdmitResult.GAME_ALREADY_STARTED;
        }

        if (members.size() >= gameType.capacity()) {
            return AdmitResult.ROOM_FULL;
        }

        return AdmitResult.ADMITTED;
    }

    /**
     * 시작 판정과 상태 전환을 하나의 원자적 동작으로 묶는다.
     *
     * <p>방장 확인, 진행 상태 확인, 최소 인원 확인, 오목 정원 일치 확인, 전원 준비 완료 확인을
     * 거쳐 통과하면 상태를 {@link RoomStatus#IN_PROGRESS} 로 바꾼다. 이 전체가 하나의 동기화
     * 블록에서 이뤄지므로, 이 판정 도중에 {@link #admit(GameParticipant, RoomStatus)} 가 끼어들어
     * 멤버 구성을 바꿔치기할 수 없다.
     */
    public synchronized StartResult beginGame(String requesterParticipantId, int minPlayers) {
        if (!hostParticipantId.equals(requesterParticipantId)) {
            return StartResult.NOT_HOST;
        }

        if (status != RoomStatus.WAITING) {
            return StartResult.NOT_WAITING;
        }

        if (members.size() < minPlayers) {
            return StartResult.NOT_ENOUGH_PLAYERS;
        }

        if (gameType == GameType.OMOK && members.size() != GameType.OMOK.capacity()) {
            return StartResult.OMOK_REQUIRES_TWO;
        }

        boolean allReady = members.values().stream().allMatch(RoomMember::ready);
        if (!allReady) {
            return StartResult.NOT_ALL_READY;
        }

        status = RoomStatus.IN_PROGRESS;
        return StartResult.STARTED;
    }

    /** {@link #admit(GameParticipant, RoomStatus)} 의 결과. */
    public enum AdmitResult {
        /** 이미 멤버였고, 연결 상태만 CONNECTED 로 되돌렸다. */
        RECONNECTED,
        /** 신규 참가자로 추가됐다. */
        ADMITTED,
        /** {@code expectedStatus} 와 방의 현재 상태가 달라 거절됐다. */
        GAME_ALREADY_STARTED,
        /** 정원이 찼다. */
        ROOM_FULL
    }

    /** {@link #beginGame(String, int)} 의 결과. */
    public enum StartResult {
        /** 상태를 {@link RoomStatus#IN_PROGRESS} 로 바꿨다. */
        STARTED,
        /** 요청자가 방장이 아니다. */
        NOT_HOST,
        /** 방이 이미 {@link RoomStatus#WAITING} 이 아니다. */
        NOT_WAITING,
        /** 최소 인원 미달이다. */
        NOT_ENOUGH_PLAYERS,
        /** 오목인데 정원과 인원이 일치하지 않는다. */
        OMOK_REQUIRES_TWO,
        /** 전원 준비 완료 상태가 아니다. */
        NOT_ALL_READY
    }
}
