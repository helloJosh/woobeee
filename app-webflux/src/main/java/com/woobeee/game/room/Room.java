package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 방 하나의 인메모리 상태. 스레드 안전하지 않다 — 방 명령 큐가 접근을 직렬화한다(Task 9).
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

    public String hostParticipantId() {
        return hostParticipantId;
    }

    public RoomStatus status() {
        return status;
    }

    public List<RoomMember> members() {
        return List.copyOf(members.values());
    }

    public Optional<RoomMember> member(String participantId) {
        return Optional.ofNullable(members.get(participantId));
    }

    public boolean isFull() {
        return members.size() >= gameType.capacity();
    }

    public void addMember(GameParticipant participant) {
        members.put(participant.participantId(), new RoomMember(participant));
    }

    public void removeMember(String participantId) {
        members.remove(participantId);
    }

    public void setReady(String participantId, boolean ready) {
        member(participantId).ifPresent(m -> m.ready(ready));
    }

    public void setConnection(String participantId, ConnectionState connection) {
        member(participantId).ifPresent(m -> m.connection(connection));
    }

    public void setStatus(RoomStatus status) {
        this.status = status;
    }

    /** 방장이 빠진 뒤 참가 순서상 다음 사람을 방장으로 세운다. 아무도 없으면 그대로 둔다. */
    public void promoteNextHost() {
        if (members.containsKey(hostParticipantId)) {
            return;
        }
        members.keySet().stream().findFirst().ifPresent(next -> hostParticipantId = next);
    }
}
