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
 * <p>이 동기화는 이 인스턴스 안의 상태(멤버 맵, status, hostParticipantId)가 손상되거나
 * ({@code ConcurrentModificationException} 등) 유실되지 않음을 보장할 뿐이다. 정원 확인 후
 * 추가처럼 여러 번의 호출을 조합하는 상위 로직(예: {@code RoomService.join})까지 원자적으로
 * 만들지는 않는다 — 그 시퀀스를 원자적으로 만들려면 호출자가 별도로 동기화하거나 큐를 둬야 한다.
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
}
