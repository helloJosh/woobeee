package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomRegistry {
    public static final Duration ROOM_TTL = Duration.ofHours(6);

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final GameIdGenerator idGenerator;
    private final Clock clock;

    public RoomRegistry(GameIdGenerator idGenerator, Clock clock) {
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    public Room create(GameType gameType, GameParticipant host) {
        Room room = new Room(
                idGenerator.nextRoomId(),
                idGenerator.nextInviteCode(),
                gameType,
                clock.instant(),
                host
        );
        rooms.put(room.roomId(), room);
        return room;
    }

    public Optional<Room> find(String roomId) {
        return Optional.ofNullable(rooms.get(roomId));
    }

    public void remove(String roomId) {
        rooms.remove(roomId);
    }

    /** TTL을 넘긴 방을 지우고 지운 개수를 돌려준다. */
    public int sweepExpired(Instant now) {
        Instant cutoff = now.minus(ROOM_TTL);
        int before = rooms.size();
        rooms.values().removeIf(room -> room.createdAt().isBefore(cutoff));
        return before - rooms.size();
    }

    /** TTL을 넘긴 방의 id 목록. 지우기 전에 허브를 닫아야 해서 따로 뽑는다. */
    public List<String> expiredRoomIds(Instant now) {
        Instant cutoff = now.minus(ROOM_TTL);
        return rooms.values().stream()
                .filter(room -> room.createdAt().isBefore(cutoff))
                .map(Room::roomId)
                .toList();
    }
}
