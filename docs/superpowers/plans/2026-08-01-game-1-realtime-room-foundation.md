# Game Plan 1 — Realtime Room Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two people — one member, one guest — open an invite link, connect over WebSocket, and see each other's nickname and READY state update live.

**Architecture:** All game state lives in memory inside the single `app-webflux` process — including the room registry, which is a `ConcurrentHashMap`, not Redis. Redis holds only guest tokens. A single `/ws/game` WebSocket carries join/leave/ready/start. Room mutation is serialised per room by synchronising on the `Room` aggregate, so concurrent inputs from different event loops cannot interleave.

> Two claims in the original version of this line were wrong and are corrected above: Redis never held the room registry, and the "per-room command queue" it promised was never built. The final whole-branch review caught both — the missing serialisation was a live data race, fixed by synchronising `Room`.

**Tech Stack:** Java 25, Spring Boot 4.0.5, Spring WebFlux (Netty), Reactor, `ReactiveStringRedisTemplate` (from `core`), R2DBC (read-only against `members`), JUnit 5 + Mockito + reactor-test.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-01-game-omok-dodge-design.md`
- **No blocking calls in `app-webflux`.** No JPA, no JDBC, no `StringRedisTemplate`, no `S3Client`. Verified by the dependency check below.
- **`core` must stay free of any web stack.** Do not add anything to `core` in this plan.
- **`members` is read-only from `app-webflux`.** Writes belong to `app-mvc` alone.
- **Do not touch the core token contract** (`AuthTokenType`, `TokenMetadata`). `AuthTokenTypeTest` must pass unchanged.
- Package root for all new code: `com.woobeee.game`.
- `participantId` format: members `m:{memberId}`, guests `g:{uuid}`.
- Nickname rule: trimmed length 1–20, no ISO control characters.
- Room capacity: `OMOK` = 2, `DODGE` = 8. Room TTL 6 hours. Disconnect grace 30 seconds. `JOIN` deadline after socket open: 10 seconds.
- Every task ends with a commit.

**Verification commands** (run before any commit that touches `app-webflux`):

```bash
./mvnw -pl core,app-mvc,app-webflux -am test
./mvnw -pl app-webflux dependency:tree \
  | grep -E "spring-boot-starter-jdbc|spring-boot-starter-data-jpa|org\.postgresql:postgresql:|awssdk:apache-client" \
  && echo "FAIL: blocking client leaked into app-webflux" || echo "OK"
```

---

### Task 1: Participant identity value types

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/ParticipantKind.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/GameParticipant.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/identity/GameParticipantTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `GameParticipant.member(long memberId, String displayName)` and `GameParticipant.guest(String guestId, String displayName)`, both returning `GameParticipant(String participantId, String displayName, ParticipantKind kind, Long memberId)`. Every later task builds participants through these two factories.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GameParticipantTest {

    @Test
    void memberIdIsPrefixedSoItCannotCollideWithAGuestId() {
        GameParticipant participant = GameParticipant.member(11L, "nick");

        assertThat(participant.participantId()).isEqualTo("m:11");
        assertThat(participant.kind()).isEqualTo(ParticipantKind.MEMBER);
        assertThat(participant.memberId()).isEqualTo(11L);
        assertThat(participant.displayName()).isEqualTo("nick");
    }

    @Test
    void guestIdIsPrefixedAndCarriesNoMemberId() {
        GameParticipant participant = GameParticipant.guest("ab12", "손님");

        assertThat(participant.participantId()).isEqualTo("g:ab12");
        assertThat(participant.kind()).isEqualTo(ParticipantKind.GUEST);
        assertThat(participant.memberId()).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=GameParticipantTest`
Expected: FAIL — compilation error, `GameParticipant` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ParticipantKind.java`:

```java
package com.woobeee.game.identity;

public enum ParticipantKind {
    MEMBER,
    GUEST
}
```

`GameParticipant.java`:

```java
package com.woobeee.game.identity;

/**
 * 회원과 게스트를 하나로 다루는 참가자 식별자.
 *
 * <p>participantId 에 접두사를 두는 이유는 회원 11번과 게스트가 같은 문자열을 갖는 사고를
 * 타입이 아니라 값에서 막기 위해서다. 결과 테이블에도 이 문자열이 그대로 들어간다.
 */
public record GameParticipant(
        String participantId,
        String displayName,
        ParticipantKind kind,
        Long memberId
) {
    public static GameParticipant member(long memberId, String displayName) {
        return new GameParticipant("m:" + memberId, displayName, ParticipantKind.MEMBER, memberId);
    }

    public static GameParticipant guest(String guestId, String displayName) {
        return new GameParticipant("g:" + guestId, displayName, ParticipantKind.GUEST, null);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=GameParticipantTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/identity app-webflux/src/test/java/com/woobeee/game/identity
git commit -m "feat(game): add GameParticipant identity for members and guests"
```

---

### Task 2: Nickname validation

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/NicknameValidator.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/identity/NicknameValidatorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static String NicknameValidator.normalize(String raw)` — returns the trimmed nickname, or throws `ResponseStatusException(BAD_REQUEST)`. Task 5 and Task 7 call it.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NicknameValidatorTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(NicknameValidator.normalize("  손님  ")).isEqualTo("손님");
    }

    @Test
    void acceptsTwentyCharacters() {
        String twenty = "a".repeat(20);
        assertThat(NicknameValidator.normalize(twenty)).isEqualTo(twenty);
    }

    @Test
    void rejectsTwentyOneCharacters() {
        assertThatThrownBy(() -> NicknameValidator.normalize("a".repeat(21)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsBlankAndNull() {
        assertThatThrownBy(() -> NicknameValidator.normalize("   "))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> NicknameValidator.normalize(null))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void rejectsControlCharacters() {
        assertThatThrownBy(() -> NicknameValidator.normalize("badname"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=NicknameValidatorTest`
Expected: FAIL — `NicknameValidator` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class NicknameValidator {
    private static final int MAX_LENGTH = 20;

    private NicknameValidator() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            throw badRequest();
        }

        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
            throw badRequest();
        }

        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw badRequest();
            }
        }

        return trimmed;
    }

    private static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nickname must be 1-20 visible characters");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=NicknameValidatorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/identity/NicknameValidator.java app-webflux/src/test/java/com/woobeee/game/identity/NicknameValidatorTest.java
git commit -m "feat(game): validate participant nicknames"
```

---

### Task 3: Room model and in-memory registry

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/room/GameType.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/RoomStatus.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/ConnectionState.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/RoomMember.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/Room.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/GameIdGenerator.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/room/UuidGameIdGenerator.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/room/RoomTest.java`

**Interfaces:**
- Consumes: `GameParticipant` (Task 1).
- Produces:
  - `GameType.OMOK` / `GameType.DODGE`, each with `int capacity()`.
  - `RoomStatus.WAITING | IN_PROGRESS | FINISHED`, `ConnectionState.CONNECTED | DISCONNECTED`.
  - `Room` with `String roomId()`, `String inviteCode()`, `GameType gameType()`, `RoomStatus status()`, `String hostParticipantId()`, `List<RoomMember> members()`, `Optional<RoomMember> member(String participantId)`, `Instant createdAt()`, and the mutators `addMember`, `removeMember`, `setReady`, `setConnection`, `setStatus`, `promoteNextHost`.
  - `RoomMember` with `GameParticipant participant()`, `boolean ready()`, `ConnectionState connection()`.
  - `GameIdGenerator` with `String nextRoomId()`, `String nextInviteCode()`, `String nextGuestId()`, `int nextSeed()`.

`nextSeed()` is unused in this plan; Plan 3 needs it and defining the interface once avoids a breaking change later.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTest {

    private Room newRoom() {
        return new Room("room-1", "invite-1", GameType.OMOK, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));
    }

    @Test
    void creatorBecomesHostAndFirstMember() {
        Room room = newRoom();

        assertThat(room.hostParticipantId()).isEqualTo("m:11");
        assertThat(room.members()).hasSize(1);
        assertThat(room.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.members().getFirst().connection()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(room.members().getFirst().ready()).isFalse();
    }

    @Test
    void membersKeepJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        assertThat(room.members().stream().map(m -> m.participant().displayName()))
                .containsExactly("host", "second", "third");
    }

    @Test
    void promotingHostPicksTheNextMemberInJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        room.removeMember("m:11");
        room.promoteNextHost();

        assertThat(room.hostParticipantId()).isEqualTo("g:a");
    }

    @Test
    void omokHoldsTwoAndDodgeHoldsEight() {
        assertThat(GameType.OMOK.capacity()).isEqualTo(2);
        assertThat(GameType.DODGE.capacity()).isEqualTo(8);
    }

    @Test
    void connectionAndReadyAreTracked() {
        Room room = newRoom();

        room.setReady("m:11", true);
        room.setConnection("m:11", ConnectionState.DISCONNECTED);

        RoomMember member = room.member("m:11").orElseThrow();
        assertThat(member.ready()).isTrue();
        assertThat(member.connection()).isEqualTo(ConnectionState.DISCONNECTED);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomTest`
Expected: FAIL — `Room` does not exist.

- [ ] **Step 3: Write minimal implementation**

`GameType.java`:

```java
package com.woobeee.game.room;

public enum GameType {
    OMOK(2),
    DODGE(8);

    private final int capacity;

    GameType(int capacity) {
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }
}
```

`RoomStatus.java`:

```java
package com.woobeee.game.room;

public enum RoomStatus {
    WAITING,
    IN_PROGRESS,
    FINISHED
}
```

`ConnectionState.java`:

```java
package com.woobeee.game.room;

public enum ConnectionState {
    CONNECTED,
    DISCONNECTED
}
```

`RoomMember.java`:

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;

public final class RoomMember {
    private final GameParticipant participant;
    private boolean ready;
    private ConnectionState connection;

    RoomMember(GameParticipant participant) {
        this.participant = participant;
        this.ready = false;
        this.connection = ConnectionState.CONNECTED;
    }

    public GameParticipant participant() {
        return participant;
    }

    public boolean ready() {
        return ready;
    }

    public ConnectionState connection() {
        return connection;
    }

    void ready(boolean ready) {
        this.ready = ready;
    }

    void connection(ConnectionState connection) {
        this.connection = connection;
    }
}
```

`Room.java`:

```java
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
```

`GameIdGenerator.java`:

```java
package com.woobeee.game.room;

public interface GameIdGenerator {
    String nextRoomId();

    String nextInviteCode();

    String nextGuestId();

    /** Plan 3의 장애물 생성 PRNG 시드. xorshift32는 0에서 멈추므로 0을 반환하면 안 된다. */
    int nextSeed();
}
```

`UuidGameIdGenerator.java`:

```java
package com.woobeee.game.room;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class UuidGameIdGenerator implements GameIdGenerator {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public String nextRoomId() {
        return token(16);
    }

    @Override
    public String nextInviteCode() {
        return token(6);
    }

    @Override
    public String nextGuestId() {
        return token(12);
    }

    @Override
    public int nextSeed() {
        int seed = RANDOM.nextInt();
        return seed == 0 ? 1 : seed;
    }

    private String token(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/room app-webflux/src/test/java/com/woobeee/game/room
git commit -m "feat(game): add room model, capacity, and id generation"
```

---

### Task 4: Room registry with TTL sweep

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/room/RoomRegistry.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/room/RoomRegistryTest.java`

**Interfaces:**
- Consumes: `Room`, `GameType`, `GameIdGenerator` (Task 3); `GameParticipant` (Task 1).
- Produces: `RoomRegistry` with `Room create(GameType gameType, GameParticipant host)`, `Optional<Room> find(String roomId)`, `void remove(String roomId)`, `int sweepExpired(Instant now)`. The registry takes a `Clock` so tests control time.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoomRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private final AtomicInteger counter = new AtomicInteger();

    private final GameIdGenerator ids = new GameIdGenerator() {
        @Override
        public String nextRoomId() {
            return "room-" + counter.incrementAndGet();
        }

        @Override
        public String nextInviteCode() {
            return "invite-" + counter.get();
        }

        @Override
        public String nextGuestId() {
            return "guest-" + counter.get();
        }

        @Override
        public int nextSeed() {
            return 42;
        }
    };

    private RoomRegistry registry() {
        return new RoomRegistry(ids, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createStoresRoomAndMakesItFindable() {
        RoomRegistry registry = registry();

        Room room = registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        assertThat(room.roomId()).isEqualTo("room-1");
        assertThat(room.inviteCode()).isEqualTo("invite-1");
        assertThat(registry.find("room-1")).containsSame(room);
    }

    @Test
    void removedRoomIsGone() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        registry.remove("room-1");

        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepDropsRoomsOlderThanSixHoursAndKeepsTheRest() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        int removed = registry.sweepExpired(NOW.plusSeconds(6 * 3600 + 1));

        assertThat(removed).isEqualTo(1);
        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepKeepsRoomsInsideTheWindow() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        int removed = registry.sweepExpired(NOW.plusSeconds(3600));

        assertThat(removed).isZero();
        assertThat(registry.find("room-1")).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomRegistryTest`
Expected: FAIL — `RoomRegistry` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
}
```

Also register a `Clock` bean. Create `app-webflux/src/main/java/com/woobeee/game/GameConfig.java`:

```java
package com.woobeee.game;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class GameConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomRegistryTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/room/RoomRegistry.java app-webflux/src/main/java/com/woobeee/game/GameConfig.java app-webflux/src/test/java/com/woobeee/game/room/RoomRegistryTest.java
git commit -m "feat(game): add room registry with six-hour TTL sweep"
```

---

### Task 5: Room service — join, ready, leave, host transfer

Covers GAME-AC-02, GAME-AC-04, GAME-AC-05, GAME-AC-06, GAME-AC-08, GAME-AC-09.

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/room/RoomService.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/room/RoomServiceTest.java`

**Interfaces:**
- Consumes: `RoomRegistry`, `Room`, `GameType`, `RoomStatus`, `ConnectionState` (Tasks 3–4); `GameParticipant` (Task 1).
- Produces: `RoomService` with
  - `Room create(GameType gameType, GameParticipant host)`
  - `Room requireRoom(String roomId, String inviteCode)` — 404 unknown room, 403 wrong invite code
  - `Room join(String roomId, String inviteCode, GameParticipant participant)`
  - `Room setReady(String roomId, String participantId, boolean ready)`
  - `void markDisconnected(String roomId, String participantId)`
  - `void confirmLeave(String roomId, String participantId)`
  - `Room start(String roomId, String requesterParticipantId)`

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomServiceTest {

    private static final GameParticipant HOST = GameParticipant.member(11L, "host");
    private static final GameParticipant GUEST = GameParticipant.guest("a", "guest");

    private RoomRegistry registry;
    private RoomService service;

    @BeforeEach
    void setUp() {
        AtomicInteger counter = new AtomicInteger();
        GameIdGenerator ids = new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-" + counter.incrementAndGet();
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "guest";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
        registry = new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        service = new RoomService(registry);
    }

    private HttpStatus statusOf(Throwable throwable) {
        return (HttpStatus) ((ResponseStatusException) throwable).getStatusCode();
    }

    /** GAME-AC-02 */
    @Test
    void wrongInviteCodeIsForbidden() {
        Room room = service.create(GameType.OMOK, HOST);

        assertThatThrownBy(() -> service.requireRoom(room.roomId(), "wrong"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void unknownRoomIsNotFound() {
        assertThatThrownBy(() -> service.requireRoom("nope", "code"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** GAME-AC-04 */
    @Test
    void joiningAFullRoomIsRejected() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);

        assertThatThrownBy(() -> service.join(room.roomId(), "code", GameParticipant.guest("b", "third")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    /** GAME-AC-05 */
    @Test
    void newParticipantCannotJoinAGameInProgress() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);
        service.start(room.roomId(), HOST.participantId());

        assertThatThrownBy(() -> service.join(room.roomId(), "code", GameParticipant.guest("b", "late")))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    /** GAME-AC-05 */
    @Test
    void existingParticipantMayReconnectDuringAGameInProgress() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);
        service.start(room.roomId(), HOST.participantId());
        service.markDisconnected(room.roomId(), GUEST.participantId());

        Room rejoined = service.join(room.roomId(), "code", GUEST);

        assertThat(rejoined.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.CONNECTED);
        assertThat(rejoined.members()).hasSize(2);
    }

    /** GAME-AC-08 */
    @Test
    void disconnectKeepsTheSeatUntilTheGraceExpires() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.markDisconnected(room.roomId(), GUEST.participantId());

        assertThat(room.members()).hasSize(2);
        assertThat(room.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.DISCONNECTED);

        service.confirmLeave(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isEmpty();
    }

    /** GAME-AC-08 */
    @Test
    void confirmLeaveIsIgnoredWhenTheParticipantReconnectedFirst() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.markDisconnected(room.roomId(), GUEST.participantId());
        service.join(room.roomId(), "code", GUEST);

        service.confirmLeave(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isPresent();
    }

    /** GAME-AC-06 */
    @Test
    void hostLeavingPromotesTheNextMember() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.confirmLeave(room.roomId(), HOST.participantId());

        assertThat(room.hostParticipantId()).isEqualTo(GUEST.participantId());
    }

    /** GAME-AC-06 */
    @Test
    void lastMemberLeavingDestroysTheRoom() {
        Room room = service.create(GameType.OMOK, HOST);

        service.confirmLeave(room.roomId(), HOST.participantId());

        assertThat(registry.find(room.roomId())).isEmpty();
    }

    @Test
    void startRequiresTheHost() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);

        assertThatThrownBy(() -> service.start(room.roomId(), GUEST.participantId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void omokNeedsTwoReadyMembersToStart() {
        Room room = service.create(GameType.OMOK, HOST);
        service.setReady(room.roomId(), HOST.participantId(), true);

        assertThatThrownBy(() -> service.start(room.roomId(), HOST.participantId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void startFlipsStatusToInProgress() {
        Room room = service.create(GameType.OMOK, HOST);
        service.join(room.roomId(), "code", GUEST);
        service.setReady(room.roomId(), HOST.participantId(), true);
        service.setReady(room.roomId(), GUEST.participantId(), true);

        service.start(room.roomId(), HOST.participantId());

        assertThat(room.status()).isEqualTo(RoomStatus.IN_PROGRESS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomServiceTest`
Expected: FAIL — `RoomService` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
                .ifPresent(room -> room.setConnection(participantId, ConnectionState.DISCONNECTED));
    }

    /**
     * 이탈 확정. 유예 만료 타이머와 명시적 LEAVE 가 같이 부른다.
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomServiceTest`
Expected: PASS, 12 tests.

- [ ] **Step 5: Add the GAME-AC-09 test for explicit leave**

```java
    /** GAME-AC-09 */
    @Test
    void explicitLeaveRemovesTheMemberWithoutGrace() {
        Room room = service.create(GameType.DODGE, HOST);
        service.join(room.roomId(), "code", GUEST);

        service.leaveNow(room.roomId(), GUEST.participantId());

        assertThat(room.member(GUEST.participantId())).isEmpty();
    }
```

Run: `./mvnw -pl app-webflux test -Dtest=RoomServiceTest`
Expected: PASS, 13 tests.

- [ ] **Step 6: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/room/RoomService.java app-webflux/src/test/java/com/woobeee/game/room/RoomServiceTest.java
git commit -m "feat(game): add room join, ready, leave, and host transfer rules"
```

---

### Task 6: Member nickname reader over R2DBC

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/MemberReader.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/identity/MemberReaderTest.java`

**Interfaces:**
- Consumes: Spring `DatabaseClient`.
- Produces: `MemberReader.findNickname(long memberId)` returning `Mono<String>`, empty when the member does not exist. Task 7 and Task 10 use it.

The `members` table is owned by `app-mvc`. This class only reads.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.identity;

import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemberReaderTest {

    @Test
    void returnsNicknameForAKnownMember() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(eq("id"), eq(11L)).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.just("nick"));

        MemberReader reader = new MemberReader(client);

        StepVerifier.create(reader.findNickname(11L))
                .expectNext("nick")
                .verifyComplete();
    }

    @Test
    void completesEmptyForAnUnknownMember() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(eq("id"), eq(99L)).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.empty());

        MemberReader reader = new MemberReader(client);

        StepVerifier.create(reader.findNickname(99L))
                .verifyComplete();
    }

    @Test
    void selectsOnlyTheNicknameColumnFromMembers() {
        DatabaseClient client = mock(DatabaseClient.class, RETURNS_DEEP_STUBS);
        when(client.sql(anyString()).bind(anyString(), any()).map(any(java.util.function.Function.class)).one())
                .thenReturn(Mono.just("nick"));

        new MemberReader(client).findNickname(11L).block();

        verify(client).sql("SELECT nickname FROM members WHERE id = :id AND active = true");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=MemberReaderTest`
Expected: FAIL — `MemberReader` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.identity;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * members 는 app-mvc 가 소유하는 테이블이다. game 은 읽기만 한다.
 */
@Component
public class MemberReader {
    private static final String SELECT_NICKNAME =
            "SELECT nickname FROM members WHERE id = :id AND active = true";

    private final DatabaseClient databaseClient;

    public MemberReader(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<String> findNickname(long memberId) {
        return databaseClient.sql(SELECT_NICKNAME)
                .bind("id", memberId)
                .map((Readable row) -> row.get("nickname", String.class))
                .one();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=MemberReaderTest`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/identity/MemberReader.java app-webflux/src/test/java/com/woobeee/game/identity/MemberReaderTest.java
git commit -m "feat(game): read member nicknames over R2DBC"
```

---

### Task 7: Guest token issue and verify

Covers GAME-AC-03.

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/GuestToken.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/identity/GuestIdentityService.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/identity/GuestIdentityServiceTest.java`

**Interfaces:**
- Consumes: `NicknameValidator` (Task 2), `GameParticipant` (Task 1), `RoomService`/`Room` (Tasks 3–5), `GameIdGenerator` (Task 3), `ReactiveStringRedisTemplate`.
- Produces:
  - `GuestToken(String token, String participantId, String displayName)`
  - `GuestIdentityService.issue(String roomId, String inviteCode, String rawNickname)` → `Mono<GuestToken>`
  - `GuestIdentityService.verify(String token)` → `Mono<GameParticipant>` (empty when unknown), plus `Mono<String> roomIdOf(String token)`.

Redis layout, exactly as the spec states:

```
key   game:guest:{token}
value hash { participantId, displayName, roomId }
TTL   6 hours
```

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.identity;

import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestIdentityServiceTest {

    private ReactiveStringRedisTemplate redis;
    private ReactiveHashOperations<String, String, String> hash;
    private RoomService roomService;
    private GuestIdentityService service;
    private Room room;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        hash = mock(ReactiveHashOperations.class);
        when(redis.<String, String>opsForHash()).thenReturn(hash);
        when(hash.putAll(anyString(), any())).thenReturn(Mono.just(true));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        GameIdGenerator ids = new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-1";
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "guest-1";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
        RoomRegistry registry =
                new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        roomService = new RoomService(registry);
        room = roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        service = new GuestIdentityService(redis, roomService, ids);
    }

    /** GAME-AC-03 */
    @Test
    void issuesATokenAndStoresItAgainstTheRoom() {
        StepVerifier.create(service.issue("room-1", "code", "  손님  "))
                .assertNext(token -> {
                    assertThat(token.participantId()).isEqualTo("g:guest-1");
                    assertThat(token.displayName()).isEqualTo("손님");
                    assertThat(token.token()).isNotBlank();
                })
                .verifyComplete();

        verify(hash).putAll(anyString(), eq(Map.of(
                "participantId", "g:guest-1",
                "displayName", "손님",
                "roomId", "room-1"
        )));
        verify(redis).expire(anyString(), eq(Duration.ofHours(6)));
    }

    /** GAME-AC-03 */
    @Test
    void rejectsABlankNickname() {
        assertThatThrownBy(() -> service.issue("room-1", "code", " ").block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** GAME-AC-03 */
    @Test
    void rejectsANicknameAlreadyUsedInTheSameRoom() {
        assertThatThrownBy(() -> service.issue("room-1", "code", "host").block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** GAME-AC-02 — the invite code is checked before anything is issued. */
    @Test
    void rejectsAWrongInviteCode() {
        assertThatThrownBy(() -> service.issue("room-1", "wrong", "손님").block())
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void verifyRebuildsTheParticipantFromRedis() {
        when(hash.entries("game:guest:tok")).thenReturn(
                reactor.core.publisher.Flux.just(
                        Map.entry("participantId", "g:guest-1"),
                        Map.entry("displayName", "손님"),
                        Map.entry("roomId", "room-1")
                )
        );

        StepVerifier.create(service.verify("tok"))
                .assertNext(participant -> {
                    assertThat(participant.participantId()).isEqualTo("g:guest-1");
                    assertThat(participant.displayName()).isEqualTo("손님");
                    assertThat(participant.kind()).isEqualTo(ParticipantKind.GUEST);
                    assertThat(participant.memberId()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void verifyIsEmptyForAnUnknownToken() {
        when(hash.entries("game:guest:missing")).thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.verify("missing")).verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=GuestIdentityServiceTest`
Expected: FAIL — `GuestIdentityService` does not exist.

- [ ] **Step 3: Write minimal implementation**

`GuestToken.java`:

```java
package com.woobeee.game.identity;

public record GuestToken(
        String token,
        String participantId,
        String displayName
) {
}
```

`GuestIdentityService.java`:

```java
package com.woobeee.game.identity;

import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomMember;
import com.woobeee.game.room.RoomService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 게스트 신원은 game 도메인이 발급한다. auth 와 core 토큰 계약은 건드리지 않는다.
 */
@Service
public class GuestIdentityService {
    public static final Duration GUEST_TOKEN_TTL = Duration.ofHours(6);

    private static final String KEY_PREFIX = "game:guest:";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RoomService roomService;
    private final GameIdGenerator idGenerator;

    public GuestIdentityService(
            ReactiveStringRedisTemplate redisTemplate,
            RoomService roomService,
            GameIdGenerator idGenerator
    ) {
        this.redisTemplate = redisTemplate;
        this.roomService = roomService;
        this.idGenerator = idGenerator;
    }

    public Mono<GuestToken> issue(String roomId, String inviteCode, String rawNickname) {
        Room room = roomService.requireRoom(roomId, inviteCode);
        String nickname = NicknameValidator.normalize(rawNickname);

        boolean taken = room.members().stream()
                .map(RoomMember::participant)
                .anyMatch(participant -> participant.displayName().equals(nickname));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nickname is already used in this room");
        }

        GameParticipant participant = GameParticipant.guest(idGenerator.nextGuestId(), nickname);
        String token = newToken();
        String key = KEY_PREFIX + token;

        return redisTemplate.<String, String>opsForHash()
                .putAll(key, Map.of(
                        "participantId", participant.participantId(),
                        "displayName", participant.displayName(),
                        "roomId", roomId
                ))
                .then(redisTemplate.expire(key, GUEST_TOKEN_TTL))
                .thenReturn(new GuestToken(token, participant.participantId(), participant.displayName()));
    }

    public Mono<GameParticipant> verify(String token) {
        return entries(token).map(values -> new GameParticipant(
                values.get("participantId"),
                values.get("displayName"),
                ParticipantKind.GUEST,
                null
        ));
    }

    public Mono<String> roomIdOf(String token) {
        return entries(token).map(values -> values.get("roomId"));
    }

    private Mono<Map<String, String>> entries(String token) {
        return redisTemplate.<String, String>opsForHash()
                .entries(KEY_PREFIX + token)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .filter(values -> values.get("participantId") != null
                        && values.get("displayName") != null
                        && values.get("roomId") != null);
    }

    private String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=GuestIdentityServiceTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/identity app-webflux/src/test/java/com/woobeee/game/identity
git commit -m "feat(game): issue and verify guest tokens in the game domain"
```

---

### Task 8: Room REST API

Covers GAME-AC-01, GAME-AC-02, GAME-AC-03 at the HTTP boundary.

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/api/request/CreateRoomRequest.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/api/request/IssueGuestTokenRequest.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/api/response/CreateRoomResponse.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/api/response/RoomSummaryResponse.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/api/response/GuestTokenResponse.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/api/RoomController.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/api/RoomControllerTest.java`

**Interfaces:**
- Consumes: `RoomService` (Task 5), `GuestIdentityService` (Task 7), `MemberReader` (Task 6), `GameAuthWebFilter.PRINCIPAL_ATTRIBUTE` and `GamePrincipal` (existing).
- Produces: three endpoints wrapped in `ApiResponse` from `core`:
  - `POST /api/game/rooms` → `CreateRoomResponse(String roomId, String inviteCode, String gameType)`
  - `GET /api/game/rooms/{roomId}?invite=` → `RoomSummaryResponse(String gameType, String status, int capacity, int participantCount)`
  - `POST /api/game/rooms/{roomId}/guest-tokens` → `GuestTokenResponse(String token, String participantId, String displayName)`

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.api;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.GuestToken;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(RoomController.class)
@Import({GameAuthWebFilter.class, RoomControllerTest.Beans.class})
class RoomControllerTest {

    @TestConfiguration
    static class Beans {
        @Bean
        GameIdGenerator gameIdGenerator() {
            return new GameIdGenerator() {
                @Override
                public String nextRoomId() {
                    return "room-1";
                }

                @Override
                public String nextInviteCode() {
                    return "code";
                }

                @Override
                public String nextGuestId() {
                    return "guest-1";
                }

                @Override
                public int nextSeed() {
                    return 42;
                }
            };
        }

        @Bean
        RoomRegistry roomRegistry(GameIdGenerator ids) {
            return new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        }

        @Bean
        RoomService roomService(RoomRegistry registry) {
            return new RoomService(registry);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RoomService roomService;

    @MockitoBean
    private ReactiveTokenVerifier reactiveTokenVerifier;

    @MockitoBean
    private GuestIdentityService guestIdentityService;

    @MockitoBean
    private MemberReader memberReader;

    @BeforeEach
    void setUp() {
        when(reactiveTokenVerifier.verify(eq("tok-1")))
                .thenReturn(Mono.just(new TokenMetadata(11L, "ROLE_MEMBER", "web", "127.0.0.1")));
        when(memberReader.findNickname(11L)).thenReturn(Mono.just("host"));
    }

    /** GAME-AC-01 */
    @Test
    void createRoomIssuesRoomIdAndInviteCode() {
        webTestClient.post().uri("/api/game/rooms")
                .header("Authorization", "Bearer tok-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"gameType\":\"OMOK\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.roomId").isEqualTo("room-1")
                .jsonPath("$.data.inviteCode").isEqualTo("code")
                .jsonPath("$.data.gameType").isEqualTo("OMOK");
    }

    /** GAME-AC-01 */
    @Test
    void createRoomRequiresAMemberToken() {
        webTestClient.post().uri("/api/game/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"gameType\":\"OMOK\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void roomSummaryIsPublicWhenTheInviteCodeMatches() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        webTestClient.get().uri("/api/game/rooms/room-1?invite=code")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.gameType").isEqualTo("DODGE")
                .jsonPath("$.data.status").isEqualTo("WAITING")
                .jsonPath("$.data.capacity").isEqualTo(8)
                .jsonPath("$.data.participantCount").isEqualTo(1);
    }

    /** GAME-AC-02 */
    @Test
    void roomSummaryRejectsAWrongInviteCode() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        webTestClient.get().uri("/api/game/rooms/room-1?invite=wrong")
                .exchange()
                .expectStatus().isForbidden();
    }

    /** GAME-AC-03 */
    @Test
    void guestTokenEndpointReturnsTheIssuedToken() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));
        when(guestIdentityService.issue(eq("room-1"), eq("code"), eq("손님")))
                .thenReturn(Mono.just(new GuestToken("tok-guest", "g:guest-1", "손님")));

        webTestClient.post().uri("/api/game/rooms/room-1/guest-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"inviteCode\":\"code\",\"nickname\":\"손님\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.token").isEqualTo("tok-guest")
                .jsonPath("$.data.participantId").isEqualTo("g:guest-1")
                .jsonPath("$.data.displayName").isEqualTo("손님");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomControllerTest`
Expected: FAIL — `RoomController` does not exist.

- [ ] **Step 3: Write the request and response records**

`CreateRoomRequest.java`:

```java
package com.woobeee.game.api.request;

import com.woobeee.game.room.GameType;
import jakarta.validation.constraints.NotNull;

public record CreateRoomRequest(
        @NotNull(message = "Game type is required")
        GameType gameType
) {
}
```

`IssueGuestTokenRequest.java`:

```java
package com.woobeee.game.api.request;

import jakarta.validation.constraints.NotBlank;

public record IssueGuestTokenRequest(
        @NotBlank(message = "Invite code is required")
        String inviteCode,
        @NotBlank(message = "Nickname is required")
        String nickname
) {
}
```

`CreateRoomResponse.java`:

```java
package com.woobeee.game.api.response;

public record CreateRoomResponse(
        String roomId,
        String inviteCode,
        String gameType
) {
}
```

`RoomSummaryResponse.java`:

```java
package com.woobeee.game.api.response;

public record RoomSummaryResponse(
        String gameType,
        String status,
        int capacity,
        int participantCount
) {
}
```

`GuestTokenResponse.java`:

```java
package com.woobeee.game.api.response;

public record GuestTokenResponse(
        String token,
        String participantId,
        String displayName
) {
}
```

- [ ] **Step 4: Write the controller**

```java
package com.woobeee.game.api;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.game.api.request.CreateRoomRequest;
import com.woobeee.game.api.request.IssueGuestTokenRequest;
import com.woobeee.game.api.response.CreateRoomResponse;
import com.woobeee.game.api.response.GuestTokenResponse;
import com.woobeee.game.api.response.RoomSummaryResponse;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.GamePrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/game/rooms")
public class RoomController {
    private final RoomService roomService;
    private final GuestIdentityService guestIdentityService;
    private final MemberReader memberReader;

    public RoomController(
            RoomService roomService,
            GuestIdentityService guestIdentityService,
            MemberReader memberReader
    ) {
        this.roomService = roomService;
        this.guestIdentityService = guestIdentityService;
        this.memberReader = memberReader;
    }

    @PostMapping
    public Mono<ApiResponse<CreateRoomResponse>> create(
            @Valid @RequestBody CreateRoomRequest request,
            ServerWebExchange exchange
    ) {
        GamePrincipal principal = requirePrincipal(exchange);

        return memberReader.findNickname(principal.memberId())
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member not found")))
                .map(nickname -> {
                    Room room = roomService.create(
                            request.gameType(),
                            GameParticipant.member(principal.memberId(), nickname)
                    );
                    return ApiResponse.success(
                            new CreateRoomResponse(room.roomId(), room.inviteCode(), room.gameType().name()),
                            "Room created"
                    );
                });
    }

    @GetMapping("/{roomId}")
    public Mono<ApiResponse<RoomSummaryResponse>> summary(
            @PathVariable String roomId,
            @RequestParam("invite") String inviteCode
    ) {
        Room room = roomService.requireRoom(roomId, inviteCode);

        return Mono.just(ApiResponse.success(
                new RoomSummaryResponse(
                        room.gameType().name(),
                        room.status().name(),
                        room.gameType().capacity(),
                        room.members().size()
                ),
                "Room summary"
        ));
    }

    @PostMapping("/{roomId}/guest-tokens")
    public Mono<ApiResponse<GuestTokenResponse>> issueGuestToken(
            @PathVariable String roomId,
            @Valid @RequestBody IssueGuestTokenRequest request
    ) {
        return guestIdentityService.issue(roomId, request.inviteCode(), request.nickname())
                .map(token -> ApiResponse.success(
                        new GuestTokenResponse(token.token(), token.participantId(), token.displayName()),
                        "Guest token issued"
                ));
    }

    private GamePrincipal requirePrincipal(ServerWebExchange exchange) {
        Object principal = exchange.getAttribute(GameAuthWebFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof GamePrincipal gamePrincipal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token is required");
        }
        return gamePrincipal;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomControllerTest`
Expected: PASS, 5 tests.

- [ ] **Step 6: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/api app-webflux/src/test/java/com/woobeee/game/api/RoomControllerTest.java
git commit -m "feat(game): add room create, summary, and guest-token endpoints"
```

---

### Task 9: WebSocket message envelope and per-room broadcast hub

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/ClientMessage.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/ServerMessage.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/RoomHub.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/ws/RoomHubTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `ClientMessage(String type, Long seq, JsonNode payload)`
  - `ServerMessage(String type, Long ackSeq, Object payload)` with factories `ServerMessage.of(String type, Object payload)` and `ServerMessage.ack(String type, Long ackSeq, Object payload)`
  - `RoomHub` with `Flux<ServerMessage> subscribe(String roomId)`, `void broadcast(String roomId, ServerMessage message)`, `void close(String roomId)`

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.ws;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

class RoomHubTest {

    @Test
    void subscribersOfTheSameRoomReceiveBroadcasts() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> {
                    hub.broadcast("room-1", ServerMessage.of("ROOM_STATE", Map.of("n", 1)));
                    hub.broadcast("room-1", ServerMessage.of("ROOM_STATE", Map.of("n", 2)));
                })
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .verifyComplete();
    }

    @Test
    void broadcastsDoNotLeakAcrossRooms() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> {
                    hub.broadcast("room-2", ServerMessage.of("OTHER", Map.of()));
                    hub.broadcast("room-1", ServerMessage.of("MINE", Map.of()));
                })
                .expectNextMatches(message -> message.type().equals("MINE"))
                .verifyComplete();
    }

    @Test
    void closingARoomCompletesItsSubscribers() {
        RoomHub hub = new RoomHub();

        StepVerifier.create(hub.subscribe("room-1"))
                .then(() -> hub.close("room-1"))
                .verifyComplete();
    }

    @Test
    void broadcastToARoomWithNoSubscribersIsANoop() {
        RoomHub hub = new RoomHub();

        hub.broadcast("ghost", ServerMessage.of("ROOM_STATE", Map.of()));

        StepVerifier.create(hub.subscribe("ghost").take(Duration.ofMillis(50)))
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomHubTest`
Expected: FAIL — `RoomHub` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ClientMessage.java`:

```java
package com.woobeee.game.ws;

import com.fasterxml.jackson.databind.JsonNode;

public record ClientMessage(
        String type,
        Long seq,
        JsonNode payload
) {
}
```

`ServerMessage.java`:

```java
package com.woobeee.game.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerMessage(
        String type,
        Long ackSeq,
        Object payload
) {
    public static ServerMessage of(String type, Object payload) {
        return new ServerMessage(type, null, payload);
    }

    public static ServerMessage ack(String type, Long ackSeq, Object payload) {
        return new ServerMessage(type, ackSeq, payload);
    }
}
```

`RoomHub.java`:

```java
package com.woobeee.game.ws;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방마다 하나씩 두는 브로드캐스트 채널.
 *
 * <p>multicast + onBackpressureBuffer 를 쓴다. 느린 구독자 하나가 방 전체를 막지 않도록
 * 버퍼가 차면 그 구독자만 끊긴다.
 */
@Component
public class RoomHub {
    private final Map<String, Sinks.Many<ServerMessage>> sinks = new ConcurrentHashMap<>();

    public Flux<ServerMessage> subscribe(String roomId) {
        return sinkFor(roomId).asFlux();
    }

    public void broadcast(String roomId, ServerMessage message) {
        Sinks.Many<ServerMessage> sink = sinks.get(roomId);
        if (sink != null) {
            sink.tryEmitNext(message);
        }
    }

    public void close(String roomId) {
        Sinks.Many<ServerMessage> sink = sinks.remove(roomId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private Sinks.Many<ServerMessage> sinkFor(String roomId) {
        return sinks.computeIfAbsent(
                roomId,
                key -> Sinks.many().multicast().onBackpressureBuffer(256, false)
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomHubTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/ws app-webflux/src/test/java/com/woobeee/game/ws
git commit -m "feat(game): add websocket envelope and per-room broadcast hub"
```

---

### Task 10: Session authentication — resolve a JOIN token to a participant

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/JoinAuthenticator.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/ws/JoinAuthenticatorTest.java`

**Interfaces:**
- Consumes: `ReactiveTokenVerifier` (existing), `MemberReader` (Task 6), `GuestIdentityService` (Task 7), `GameParticipant` (Task 1).
- Produces: `JoinAuthenticator.authenticate(String roomId, String token)` → `Mono<GameParticipant>`, erroring with `ResponseStatusException(UNAUTHORIZED)` when neither a member nor a guest token matches, and when a guest token belongs to a different room.

The member path is tried first. A guest token is scoped to one room, so it is checked against `roomId`.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.ws;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.identity.ParticipantKind;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JoinAuthenticatorTest {

    private ReactiveTokenVerifier tokenVerifier;
    private MemberReader memberReader;
    private GuestIdentityService guestIdentityService;
    private JoinAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        tokenVerifier = mock(ReactiveTokenVerifier.class);
        memberReader = mock(MemberReader.class);
        guestIdentityService = mock(GuestIdentityService.class);
        authenticator = new JoinAuthenticator(tokenVerifier, memberReader, guestIdentityService);
    }

    @Test
    void resolvesAMemberAccessToken() {
        when(tokenVerifier.verify("tok-m"))
                .thenReturn(Mono.just(new TokenMetadata(11L, "ROLE_MEMBER", "web", "127.0.0.1")));
        when(memberReader.findNickname(11L)).thenReturn(Mono.just("nick"));

        StepVerifier.create(authenticator.authenticate("room-1", "tok-m"))
                .assertNext(participant -> {
                    assertThat(participant.participantId()).isEqualTo("m:11");
                    assertThat(participant.kind()).isEqualTo(ParticipantKind.MEMBER);
                    assertThat(participant.displayName()).isEqualTo("nick");
                })
                .verifyComplete();
    }

    @Test
    void fallsBackToAGuestTokenScopedToTheSameRoom() {
        when(tokenVerifier.verify("tok-g")).thenReturn(Mono.empty());
        when(guestIdentityService.roomIdOf("tok-g")).thenReturn(Mono.just("room-1"));
        when(guestIdentityService.verify("tok-g"))
                .thenReturn(Mono.just(GameParticipant.guest("a", "손님")));

        StepVerifier.create(authenticator.authenticate("room-1", "tok-g"))
                .assertNext(participant -> assertThat(participant.participantId()).isEqualTo("g:a"))
                .verifyComplete();
    }

    @Test
    void rejectsAGuestTokenIssuedForAnotherRoom() {
        when(tokenVerifier.verify("tok-g")).thenReturn(Mono.empty());
        when(guestIdentityService.roomIdOf("tok-g")).thenReturn(Mono.just("room-2"));

        StepVerifier.create(authenticator.authenticate("room-1", "tok-g"))
                .expectErrorSatisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verify();
    }

    @Test
    void rejectsAnUnknownToken() {
        when(tokenVerifier.verify("nope")).thenReturn(Mono.empty());
        when(guestIdentityService.roomIdOf("nope")).thenReturn(Mono.empty());

        StepVerifier.create(authenticator.authenticate("room-1", "nope"))
                .expectErrorSatisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verify();
    }

    @Test
    void rejectsAMemberTokenWhoseMemberRowIsGone() {
        when(tokenVerifier.verify("tok-m"))
                .thenReturn(Mono.just(new TokenMetadata(11L, "ROLE_MEMBER", "web", "127.0.0.1")));
        when(memberReader.findNickname(11L)).thenReturn(Mono.empty());

        StepVerifier.create(authenticator.authenticate("room-1", "tok-m"))
                .expectErrorSatisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED))
                .verify();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=JoinAuthenticatorTest`
Expected: FAIL — `JoinAuthenticator` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * JOIN 메시지에 실려온 토큰을 참가자로 바꾼다.
 *
 * <p>회원 access token 을 먼저 보고, 아니면 게스트 토큰으로 본다. 게스트 토큰은 방 하나에만
 * 유효하므로 roomId 가 일치해야 한다 — 없으면 한 방에서 받은 토큰으로 다른 방에 들어갈 수 있다.
 */
@Component
public class JoinAuthenticator {
    private final ReactiveTokenVerifier tokenVerifier;
    private final MemberReader memberReader;
    private final GuestIdentityService guestIdentityService;

    public JoinAuthenticator(
            ReactiveTokenVerifier tokenVerifier,
            MemberReader memberReader,
            GuestIdentityService guestIdentityService
    ) {
        this.tokenVerifier = tokenVerifier;
        this.memberReader = memberReader;
        this.guestIdentityService = guestIdentityService;
    }

    public Mono<GameParticipant> authenticate(String roomId, String token) {
        return tokenVerifier.verify(token)
                .flatMap(metadata -> memberReader.findNickname(metadata.memberId())
                        .map(nickname -> GameParticipant.member(metadata.memberId(), nickname))
                        .switchIfEmpty(Mono.error(unauthorized())))
                .switchIfEmpty(Mono.defer(() -> authenticateGuest(roomId, token)));
    }

    private Mono<GameParticipant> authenticateGuest(String roomId, String token) {
        return guestIdentityService.roomIdOf(token)
                .filter(roomId::equals)
                .flatMap(matched -> guestIdentityService.verify(token))
                .switchIfEmpty(Mono.error(unauthorized()));
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid game token");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=JoinAuthenticatorTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/ws/JoinAuthenticator.java app-webflux/src/test/java/com/woobeee/game/ws/JoinAuthenticatorTest.java
git commit -m "feat(game): resolve websocket JOIN tokens to members or guests"
```

---

### Task 11: Room state projection

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/payload/RoomStatePayload.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/payload/ParticipantView.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/RoomStateProjector.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/ws/RoomStateProjectorTest.java`

**Interfaces:**
- Consumes: `Room`, `RoomMember` (Task 3).
- Produces: `static RoomStatePayload RoomStateProjector.project(Room room)` producing exactly the shape the spec fixes: `{gameType, hostParticipantId, status, participants[]}` where each participant is `{participantId, displayName, kind, ready, connection}`.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.ConnectionState;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.ws.payload.RoomStatePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomStateProjectorTest {

    @Test
    void projectsRoomAndParticipantsInJoinOrder() {
        Room room = new Room("room-1", "code", GameType.DODGE, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));
        room.setReady("m:11", true);
        room.setConnection("g:a", ConnectionState.DISCONNECTED);

        RoomStatePayload payload = RoomStateProjector.project(room);

        assertThat(payload.gameType()).isEqualTo("DODGE");
        assertThat(payload.hostParticipantId()).isEqualTo("m:11");
        assertThat(payload.status()).isEqualTo("WAITING");
        assertThat(payload.participants()).hasSize(2);

        assertThat(payload.participants().get(0).participantId()).isEqualTo("m:11");
        assertThat(payload.participants().get(0).kind()).isEqualTo("MEMBER");
        assertThat(payload.participants().get(0).ready()).isTrue();
        assertThat(payload.participants().get(0).connection()).isEqualTo("CONNECTED");

        assertThat(payload.participants().get(1).participantId()).isEqualTo("g:a");
        assertThat(payload.participants().get(1).kind()).isEqualTo("GUEST");
        assertThat(payload.participants().get(1).ready()).isFalse();
        assertThat(payload.participants().get(1).connection()).isEqualTo("DISCONNECTED");
    }

    @Test
    void doesNotLeakMemberIds() {
        Room room = new Room("room-1", "code", GameType.OMOK, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));

        RoomStatePayload payload = RoomStateProjector.project(room);

        assertThat(payload.participants().getFirst()).hasNoNullFieldsOrProperties();
        assertThat(ParticipantViewFields.names()).doesNotContain("memberId");
    }

    /** 리플렉션 대신 명시적으로 필드 이름을 적어두어 프로젝션이 커질 때 눈에 띄게 한다. */
    static final class ParticipantViewFields {
        static java.util.List<String> names() {
            return java.util.Arrays.stream(
                            com.woobeee.game.ws.payload.ParticipantView.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomStateProjectorTest`
Expected: FAIL — `RoomStatePayload` does not exist.

- [ ] **Step 3: Write minimal implementation**

`ParticipantView.java`:

```java
package com.woobeee.game.ws.payload;

public record ParticipantView(
        String participantId,
        String displayName,
        String kind,
        boolean ready,
        String connection
) {
}
```

`RoomStatePayload.java`:

```java
package com.woobeee.game.ws.payload;

import java.util.List;

public record RoomStatePayload(
        String gameType,
        String hostParticipantId,
        String status,
        List<ParticipantView> participants
) {
}
```

`RoomStateProjector.java`:

```java
package com.woobeee.game.ws;

import com.woobeee.game.room.Room;
import com.woobeee.game.ws.payload.ParticipantView;
import com.woobeee.game.ws.payload.RoomStatePayload;

/**
 * 방 상태를 클라이언트에 보낼 모양으로 바꾼다. memberId 는 내보내지 않는다 —
 * 참가자 목록에 회원 내부 식별자를 실을 이유가 없다.
 */
public final class RoomStateProjector {

    private RoomStateProjector() {
    }

    public static RoomStatePayload project(Room room) {
        return new RoomStatePayload(
                room.gameType().name(),
                room.hostParticipantId(),
                room.status().name(),
                room.members().stream()
                        .map(member -> new ParticipantView(
                                member.participant().participantId(),
                                member.participant().displayName(),
                                member.participant().kind().name(),
                                member.ready(),
                                member.connection().name()
                        ))
                        .toList()
        );
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomStateProjectorTest`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/ws/payload app-webflux/src/main/java/com/woobeee/game/ws/RoomStateProjector.java app-webflux/src/test/java/com/woobeee/game/ws/RoomStateProjectorTest.java
git commit -m "feat(game): project room state for websocket clients"
```

---

### Task 12: Room command dispatcher

This is the piece that serialises mutation. Games in Plans 2 and 3 hang off `GameCommandSink`.

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/GameCommandSink.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/RoomCommandDispatcher.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/ws/RoomCommandDispatcherTest.java`

**Interfaces:**
- Consumes: `RoomService` (Task 5), `RoomHub`, `ServerMessage` (Task 9), `RoomStateProjector` (Task 11).
- Produces:
  - `GameCommandSink` — the extension point Plans 2 and 3 implement:
    ```java
    public interface GameCommandSink {
        GameType gameType();
        void onStart(Room room);
        void onGameCommand(Room room, String participantId, ClientMessage message);
        void onParticipantGone(Room room, String participantId);
    }
    ```
  - `RoomCommandDispatcher` with `void join(...)`, `void ready(...)`, `void start(...)`, `void leaveNow(...)`, `void disconnected(...)`, `void confirmLeave(...)`, `void gameCommand(...)`. Every method broadcasts the resulting `ROOM_STATE` where the spec says it should.

`RoomCommandDispatcher` resolves the sink by `GameType`; with no sink registered (this plan) game commands are answered with an `ERROR`.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.ConnectionState;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.ws.payload.RoomStatePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomCommandDispatcherTest {

    private static final GameParticipant HOST = GameParticipant.member(11L, "host");
    private static final GameParticipant GUEST = GameParticipant.guest("a", "손님");

    private RoomService roomService;
    private RoomHub hub;
    private RoomCommandDispatcher dispatcher;
    private Room room;

    @BeforeEach
    void setUp() {
        GameIdGenerator ids = new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-1";
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "g1";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
        RoomRegistry registry =
                new RoomRegistry(ids, Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC));
        roomService = new RoomService(registry);
        hub = new RoomHub();
        dispatcher = new RoomCommandDispatcher(roomService, hub, List.of());
        room = roomService.create(GameType.OMOK, HOST);
    }

    @Test
    void joinBroadcastsRoomState() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.join("room-1", "code", GUEST))
                .assertNext(message -> {
                    assertThat(message.type()).isEqualTo("ROOM_STATE");
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void readyBroadcastsRoomState() {
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.ready("room-1", GUEST.participantId(), true))
                .assertNext(message -> {
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants().get(1).ready()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void disconnectBroadcastsTheDisconnectedState() {
        dispatcher.join("room-1", "code", GUEST);

        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.disconnected("room-1", GUEST.participantId()))
                .assertNext(message -> {
                    RoomStatePayload payload = (RoomStatePayload) message.payload();
                    assertThat(payload.participants().get(1).connection()).isEqualTo("DISCONNECTED");
                })
                .verifyComplete();

        assertThat(room.member(GUEST.participantId()).orElseThrow().connection())
                .isEqualTo(ConnectionState.DISCONNECTED);
    }

    @Test
    void startWithNoRegisteredSinkStillFlipsRoomStatusAndAnnouncesGameStart() {
        dispatcher.join("room-1", "code", GUEST);
        dispatcher.ready("room-1", HOST.participantId(), true);
        dispatcher.ready("room-1", GUEST.participantId(), true);

        StepVerifier.create(hub.subscribe("room-1").take(2))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .expectNextMatches(message -> message.type().equals("ROOM_STATE"))
                .expectNextMatches(message -> message.type().equals("GAME_START"))
                .verifyComplete();
    }

    @Test
    void closingTheLastMemberClosesTheHub() {
        StepVerifier.create(hub.subscribe("room-1"))
                .then(() -> dispatcher.leaveNow("room-1", HOST.participantId()))
                .verifyComplete();
    }

    @Test
    void aFailedCommandEmitsErrorToTheHubInsteadOfThrowing() {
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> dispatcher.start("room-1", HOST.participantId()))
                .assertNext(message -> assertThat(message.type()).isEqualTo("ERROR"))
                .verifyComplete();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomCommandDispatcherTest`
Expected: FAIL — `RoomCommandDispatcher` does not exist.

- [ ] **Step 3: Write minimal implementation**

`GameCommandSink.java`:

```java
package com.woobeee.game.ws;

import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;

/**
 * 게임별 로직이 붙는 확장점. Plan 2(오목)와 Plan 3(장애물피하기)이 각각 구현한다.
 *
 * <p>모든 메서드는 방 명령 큐 안에서 호출되므로 같은 방에 대해 동시에 불리지 않는다.
 */
public interface GameCommandSink {
    GameType gameType();

    void onStart(Room room);

    void onGameCommand(Room room, String participantId, ClientMessage message);

    void onParticipantGone(Room room, String participantId);
}
```

`RoomCommandDispatcher.java`:

```java
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
        settle(roomId, participantId, () -> roomService.confirmLeave(roomId, participantId));
    }

    public void leaveNow(String roomId, String participantId) {
        settle(roomId, participantId, () -> roomService.leaveNow(roomId, participantId));
    }

    private void settle(String roomId, String participantId, Runnable removal) {
        Optional<Room> before = roomService.findRoom(roomId);
        boolean wasMember = before.flatMap(room -> room.member(participantId)).isPresent();

        removal.run();

        Optional<Room> after = roomService.findRoom(roomId);
        if (after.isEmpty()) {
            roomHub.close(roomId);
            return;
        }

        Room room = after.get();
        if (wasMember && room.member(participantId).isEmpty()) {
            Optional.ofNullable(sinks.get(room.gameType()))
                    .ifPresent(sink -> sink.onParticipantGone(room, participantId));
        }
        broadcastRoomState(room);
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
```

- [ ] **Step 4: Add the two `RoomService` lookups the dispatcher needs**

Append to `RoomService`:

```java
    public Optional<Room> findRoom(String roomId) {
        return roomRegistry.find(roomId);
    }

    public Room requireRoomById(String roomId) {
        return roomRegistry.find(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
    }
```

Add `import java.util.Optional;` to `RoomService`.

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomCommandDispatcherTest,RoomServiceTest`
Expected: PASS, 19 tests total.

- [ ] **Step 6: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/ws app-webflux/src/main/java/com/woobeee/game/room/RoomService.java app-webflux/src/test/java/com/woobeee/game/ws/RoomCommandDispatcherTest.java
git commit -m "feat(game): add room command dispatcher and game sink extension point"
```

---

### Task 13: WebSocket handler with JOIN deadline and disconnect grace

Covers GAME-AC-07 and wires GAME-AC-08's timer.

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/GameWebSocketHandler.java`
- Create: `app-webflux/src/main/java/com/woobeee/game/ws/GameWebSocketConfig.java`
- Test: `app-webflux/src/test/java/com/woobeee/game/ws/GameWebSocketHandlerTest.java`

**Interfaces:**
- Consumes: `JoinAuthenticator` (Task 10), `RoomCommandDispatcher` (Task 12), `RoomHub` (Task 9), `ObjectMapper`.
- Produces: a `WebSocketHandler` bean mapped at `/ws/game`. Constructor takes `(JoinAuthenticator, RoomCommandDispatcher, RoomHub, ObjectMapper, Duration joinDeadline, Duration disconnectGrace, Scheduler timerScheduler)` so tests inject a `VirtualTimeScheduler` and short durations.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameWebSocketHandlerTest {

    private JoinAuthenticator authenticator;
    private RoomCommandDispatcher dispatcher;
    private RoomHub hub;
    private VirtualTimeScheduler scheduler;
    private GameWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        authenticator = mock(JoinAuthenticator.class);
        dispatcher = mock(RoomCommandDispatcher.class);
        hub = new RoomHub();
        scheduler = VirtualTimeScheduler.create();
        handler = new GameWebSocketHandler(
                authenticator,
                dispatcher,
                hub,
                new ObjectMapper(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler
        );
    }

    private WebSocketSession sessionEmitting(String... payloads) {
        WebSocketSession session = mock(WebSocketSession.class);
        List<String> sent = new CopyOnWriteArrayList<>();

        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.fromArray(payloads).map(payload -> {
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(payload);
            return message;
        }));
        when(session.textMessage(anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return mock(WebSocketMessage.class);
        });
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close()).thenReturn(Mono.empty());
        return session;
    }

    /** GAME-AC-07 */
    @Test
    void closesTheSessionWhenJoinDoesNotArriveWithinTheDeadline() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.never());
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close()).thenReturn(Mono.empty());

        handler.handle(session).subscribe();
        scheduler.advanceTimeBy(Duration.ofSeconds(11));

        verify(session).close();
    }

    /** GAME-AC-07 */
    @Test
    void doesNotCloseTheSessionWhenJoinArrivesInTime() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}"
        );

        handler.handle(session).subscribe();
        scheduler.advanceTimeBy(Duration.ofSeconds(11));

        verify(session, never()).close();
        verify(dispatcher).join(eq("room-1"), eq("code"), any(GameParticipant.class));
    }

    /** GAME-AC-08 */
    @Test
    void schedulesConfirmLeaveAfterTheGraceWhenTheSocketEnds() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}"
        );

        handler.handle(session).subscribe();

        verify(dispatcher).disconnected("room-1", "m:11");
        verify(dispatcher, never()).confirmLeave(anyString(), anyString());

        scheduler.advanceTimeBy(Duration.ofSeconds(31));

        verify(dispatcher).confirmLeave("room-1", "m:11");
    }

    /** GAME-AC-09 */
    @Test
    void explicitLeaveGoesStraightToLeaveNow() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}",
                "{\"type\":\"LEAVE\",\"seq\":2}"
        );

        handler.handle(session).subscribe();

        verify(dispatcher).leaveNow("room-1", "m:11");
    }

    @Test
    void readyAndStartAreForwardedToTheDispatcher() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}",
                "{\"type\":\"READY\",\"seq\":2,\"payload\":{\"ready\":true}}",
                "{\"type\":\"START\",\"seq\":3}"
        );

        handler.handle(session).subscribe();

        verify(dispatcher).ready("room-1", "m:11", true);
        verify(dispatcher).start("room-1", "m:11");
    }

    @Test
    void gameSpecificMessagesAreForwardedToTheDispatcher() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}",
                "{\"type\":\"OMOK_PLACE\",\"seq\":2,\"payload\":{\"x\":7,\"y\":7}}"
        );

        handler.handle(session).subscribe();

        verify(dispatcher).gameCommand(eq("room-1"), eq("m:11"), any(ClientMessage.class));
    }

    /**
     * outbound 배선 회귀 테스트. Flux.defer 로 감싸면 구독 시점의 state 가 null 이라
     * 빈 스트림으로 끝나고 아무 메시지도 나가지 않는다 — 그 버그를 이 테스트가 잡는다.
     */
    @Test
    void hubBroadcastsReachTheSessionAfterJoin() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(11L, "host")));

        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.just(
                        "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}")
                .map(payload -> {
                    WebSocketMessage message = mock(WebSocketMessage.class);
                    when(message.getPayloadAsText()).thenReturn(payload);
                    return message;
                }).concatWith(Flux.never()));
        when(session.textMessage(anyString())).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return mock(WebSocketMessage.class);
        });
        when(session.send(any())).thenAnswer(invocation -> {
            Publisher<WebSocketMessage> messages = invocation.getArgument(0);
            return Flux.from(messages).then();
        });
        when(session.close()).thenReturn(Mono.empty());

        handler.handle(session).subscribe();
        hub.broadcast("room-1", ServerMessage.of("ROOM_STATE", java.util.Map.of("n", 1)));

        assertThat(sent).anySatisfy(text -> assertThat(text).contains("ROOM_STATE"));
    }

    @Test
    void messagesBeforeJoinAreIgnored() {
        WebSocketSession session = sessionEmitting("{\"type\":\"READY\",\"seq\":1,\"payload\":{\"ready\":true}}");

        handler.handle(session).subscribe();

        verify(dispatcher, never()).ready(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=GameWebSocketHandlerTest`
Expected: FAIL — `GameWebSocketHandler` does not exist.

- [ ] **Step 3: Write the handler**

```java
package com.woobeee.game.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * /ws/game 세션 하나의 수명을 다룬다.
 *
 * <p>브라우저 WebSocket 은 핸드셰이크에 Authorization 헤더를 붙일 수 없으므로 토큰은 첫 JOIN
 * 메시지로 받는다. 쿼리 파라미터로 받지 않는 이유는 URL 이 접근 로그에 남기 때문이다.
 */
public class GameWebSocketHandler implements WebSocketHandler {
    private final JoinAuthenticator joinAuthenticator;
    private final RoomCommandDispatcher dispatcher;
    private final RoomHub roomHub;
    private final ObjectMapper objectMapper;
    private final Duration joinDeadline;
    private final Duration disconnectGrace;
    private final Scheduler timerScheduler;

    public GameWebSocketHandler(
            JoinAuthenticator joinAuthenticator,
            RoomCommandDispatcher dispatcher,
            RoomHub roomHub,
            ObjectMapper objectMapper,
            Duration joinDeadline,
            Duration disconnectGrace,
            Scheduler timerScheduler
    ) {
        this.joinAuthenticator = joinAuthenticator;
        this.dispatcher = dispatcher;
        this.roomHub = roomHub;
        this.objectMapper = objectMapper;
        this.joinDeadline = joinDeadline;
        this.disconnectGrace = disconnectGrace;
        this.timerScheduler = timerScheduler;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        AtomicReference<SessionState> state = new AtomicReference<>(null);

        // JOIN 이 도착해야 어느 방을 구독할지 알 수 있다. send() 는 지금 구독되므로
        // Flux.defer 로 감싸면 그 시점의 state(=null)를 읽고 빈 스트림으로 끝나 버린다 —
        // 아무 메시지도 나가지 않는다. 그래서 방 id 를 Sinks.One 으로 늦게 넘긴다.
        Sinks.One<String> joinedRoomId = Sinks.one();

        Disposable joinTimer = Mono.delay(joinDeadline, timerScheduler)
                .filter(ignored -> state.get() == null)
                .flatMap(ignored -> session.close())
                .subscribe();

        Mono<Void> inbound = session.receive()
                .map(message -> message.getPayloadAsText())
                .concatMap(text -> handleText(session, state, joinedRoomId, text))
                .then();

        Mono<Void> outbound = session.send(
                joinedRoomId.asMono()
                        .flatMapMany(roomHub::subscribe)
                        .map(this::toTextMessage)
                        .map(session::textMessage)
        );

        return Mono.when(inbound, outbound)
                .doFinally(signal -> {
                    joinTimer.dispose();
                    SessionState joined = state.get();
                    if (joined == null || joined.left()) {
                        return;
                    }
                    dispatcher.disconnected(joined.roomId(), joined.participantId());
                    Mono.delay(disconnectGrace, timerScheduler)
                            .doOnNext(ignored -> dispatcher.confirmLeave(joined.roomId(), joined.participantId()))
                            .subscribe();
                });
    }

    private Mono<Void> handleText(
            WebSocketSession session,
            AtomicReference<SessionState> state,
            Sinks.One<String> joinedRoomId,
            String text
    ) {
        ClientMessage message = parse(text);
        if (message == null) {
            return Mono.empty();
        }

        SessionState joined = state.get();
        if (joined == null) {
            if (!"JOIN".equals(message.type())) {
                return Mono.empty();
            }
            return join(session, state, joinedRoomId, message);
        }

        switch (message.type()) {
            case "LEAVE" -> {
                joined.markLeft();
                dispatcher.leaveNow(joined.roomId(), joined.participantId());
            }
            case "READY" -> dispatcher.ready(
                    joined.roomId(),
                    joined.participantId(),
                    message.payload() != null && message.payload().path("ready").asBoolean(false)
            );
            case "START" -> dispatcher.start(joined.roomId(), joined.participantId());
            case "JOIN" -> {
                // 이미 참가한 세션의 중복 JOIN 은 무시한다.
            }
            default -> dispatcher.gameCommand(joined.roomId(), joined.participantId(), message);
        }
        return Mono.empty();
    }

    private Mono<Void> join(
            WebSocketSession session,
            AtomicReference<SessionState> state,
            Sinks.One<String> joinedRoomId,
            ClientMessage message
    ) {
        JsonNode payload = message.payload();
        if (payload == null) {
            return Mono.empty();
        }

        String roomId = payload.path("roomId").asText(null);
        String inviteCode = payload.path("inviteCode").asText(null);
        String token = payload.path("token").asText(null);
        if (roomId == null || inviteCode == null || token == null) {
            return session.close();
        }

        return joinAuthenticator.authenticate(roomId, token)
                .doOnNext(participant -> {
                    state.set(new SessionState(roomId, participant.participantId()));
                    // 구독을 먼저 연다. dispatcher.join 이 브로드캐스트하는 ROOM_STATE 를
                    // 이 세션도 받아야 하기 때문이다.
                    joinedRoomId.tryEmitValue(roomId);
                    dispatcher.join(roomId, inviteCode, participant);
                })
                .onErrorResume(error -> session.close().then(Mono.empty()))
                .then();
    }

    private ClientMessage parse(String text) {
        try {
            return objectMapper.readValue(text, ClientMessage.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private String toTextMessage(ServerMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception exception) {
            return "{\"type\":\"ERROR\",\"payload\":{\"message\":\"serialization failed\"}}";
        }
    }

    private static final class SessionState {
        private final String roomId;
        private final String participantId;
        private volatile boolean left;

        private SessionState(String roomId, String participantId) {
            this.roomId = roomId;
            this.participantId = participantId;
        }

        String roomId() {
            return roomId;
        }

        String participantId() {
            return participantId;
        }

        boolean left() {
            return left;
        }

        void markLeft() {
            this.left = true;
        }
    }
}
```

- [ ] **Step 4: Register the handler at `/ws/game`**

```java
package com.woobeee.game.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@Configuration
public class GameWebSocketConfig {
    public static final Duration JOIN_DEADLINE = Duration.ofSeconds(10);
    public static final Duration DISCONNECT_GRACE = Duration.ofSeconds(30);

    @Bean
    public Scheduler gameTimerScheduler() {
        return Schedulers.parallel();
    }

    @Bean
    public GameWebSocketHandler gameWebSocketHandler(
            JoinAuthenticator joinAuthenticator,
            RoomCommandDispatcher dispatcher,
            RoomHub roomHub,
            ObjectMapper objectMapper,
            Scheduler gameTimerScheduler
    ) {
        return new GameWebSocketHandler(
                joinAuthenticator,
                dispatcher,
                roomHub,
                objectMapper,
                JOIN_DEADLINE,
                DISCONNECT_GRACE,
                gameTimerScheduler
        );
    }

    @Bean
    public HandlerMapping gameWebSocketHandlerMapping(GameWebSocketHandler gameWebSocketHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.<String, WebSocketHandler>of("/ws/game", gameWebSocketHandler));
        mapping.setOrder(-1);
        return mapping;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=GameWebSocketHandlerTest`
Expected: PASS, 8 tests.

- [ ] **Step 6: Run the full suite and the dependency check**

```bash
./mvnw -pl core,app-mvc,app-webflux -am test
./mvnw -pl app-webflux dependency:tree \
  | grep -E "spring-boot-starter-jdbc|spring-boot-starter-data-jpa|org\.postgresql:postgresql:|awssdk:apache-client" \
  && echo "FAIL: blocking client leaked into app-webflux" || echo "OK"
```

Expected: BUILD SUCCESS and `OK`.

- [ ] **Step 7: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/ws app-webflux/src/test/java/com/woobeee/game/ws/GameWebSocketHandlerTest.java
git commit -m "feat(game): serve /ws/game with join deadline and disconnect grace"
```

---

### Task 14: Room TTL sweeper and documentation

**Files:**
- Create: `app-webflux/src/main/java/com/woobeee/game/room/RoomSweeper.java`
- Create: `docs/_global/adr/ADR-005-realtime-websocket.md`
- Modify: `docs/game/PRD.md`
- Modify: `CLAUDE.md`
- Test: `app-webflux/src/test/java/com/woobeee/game/room/RoomSweeperTest.java`

**Interfaces:**
- Consumes: `RoomRegistry` (Task 4), `Clock`.
- Produces: `RoomSweeper` — a `@Scheduled`-free component that exposes `void sweep()` and is driven by a `Flux.interval` started in `afterPropertiesSet`. Tests call `sweep()` directly.

- [ ] **Step 1: Write the failing test**

```java
package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoomSweeperTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private GameIdGenerator ids(AtomicInteger counter) {
        return new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-" + counter.incrementAndGet();
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "guest";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
    }

    @Test
    void sweepRemovesExpiredRoomsAndClosesTheirHubs() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        RoomHub hub = new RoomHub();
        RoomSweeper sweeper = new RoomSweeper(
                registry, hub, Clock.fixed(NOW.plusSeconds(6 * 3600 + 1), ZoneOffset.UTC));

        sweeper.sweep();

        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepKeepsFreshRooms() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        RoomSweeper sweeper = new RoomSweeper(
                registry, new RoomHub(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        sweeper.sweep();

        assertThat(registry.find("room-1")).isPresent();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -pl app-webflux test -Dtest=RoomSweeperTest`
Expected: FAIL — `RoomSweeper` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.woobeee.game.room;

import com.woobeee.game.ws.RoomHub;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Component
public class RoomSweeper {
    private static final Logger log = LoggerFactory.getLogger(RoomSweeper.class);
    private static final Duration INTERVAL = Duration.ofMinutes(10);

    private final RoomRegistry roomRegistry;
    private final RoomHub roomHub;
    private final Clock clock;

    private Disposable subscription;

    public RoomSweeper(RoomRegistry roomRegistry, RoomHub roomHub, Clock clock) {
        this.roomRegistry = roomRegistry;
        this.roomHub = roomHub;
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        subscription = Flux.interval(INTERVAL, INTERVAL)
                .doOnNext(tick -> sweep())
                .subscribe();
    }

    @PreDestroy
    void stop() {
        if (subscription != null) {
            subscription.dispose();
        }
    }

    public void sweep() {
        List<String> expired = roomRegistry.expiredRoomIds(clock.instant());
        expired.forEach(roomHub::close);
        int removed = roomRegistry.sweepExpired(clock.instant());
        if (removed > 0) {
            log.info("Swept {} expired game rooms", removed);
        }
    }
}
```

Add to `RoomRegistry`:

```java
    /** TTL을 넘긴 방의 id 목록. 지우기 전에 허브를 닫아야 해서 따로 뽑는다. */
    public List<String> expiredRoomIds(Instant now) {
        Instant cutoff = now.minus(ROOM_TTL);
        return rooms.values().stream()
                .filter(room -> room.createdAt().isBefore(cutoff))
                .map(Room::roomId)
                .toList();
    }
```

Add `import java.util.List;` to `RoomRegistry`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -pl app-webflux test -Dtest=RoomSweeperTest,RoomRegistryTest`
Expected: PASS, 6 tests.

- [ ] **Step 5: Write ADR-005**

Create `docs/_global/adr/ADR-005-realtime-websocket.md`:

```markdown
# ADR-005 — 실시간 통신은 WebSocket 단일 채널

- 상태: 채택
- 날짜: 2026-08-01
- 관련 spec: `docs/superpowers/specs/2026-08-01-game-omok-dodge-design.md`

## 맥락

게임 도메인에 오목(1:1 턴제)과 장애물피하기(최대 8인, 100ms 틱 격자 액션)를 넣는다.
`docs/game/PRD.md` 와 `CLAUDE.md` 가 실시간 통신 방식을 이 시점까지 미뤄 두었다.

## 검토한 선택지

1. **WebSocket 단일 채널**
2. **SSE(서버→클라) + POST(클라→서버)**
3. **게임별로 다르게** — 오목은 SSE+POST, 장애물피하기는 WebSocket

## 결정

**WebSocket 단일 채널.** 엔드포인트는 `/ws/game` 하나이고 두 게임이 같은 봉투를 쓴다.

## 근거

- 장애물피하기는 초당 수십 번 입력이 오간다. SSE+POST 는 입력마다 HTTP 요청 한 벌을 치르고,
  브라우저의 호스트당 커넥션 제한에도 걸린다.
- 오목만 놓고 보면 SSE+POST 로 충분하다. 그러나 방 입퇴장·참가자 목록·READY 동기화는 두 게임이
  공유하는 코드다. 전송 방식을 갈라 놓으면 그 코드를 두 벌 유지해야 한다.
- `app-webflux` 는 이미 Netty 위에 있어 Reactive `WebSocketHandler` 를 추가 의존 없이 쓴다.

## 결과

- 브라우저 WebSocket 은 핸드셰이크에 `Authorization` 헤더를 붙일 수 없다. 토큰은 첫 `JOIN`
  메시지로 받고, 10초 안에 오지 않으면 서버가 세션을 닫는다. 쿼리 파라미터는 접근 로그에 남으므로
  쓰지 않는다.
- **게임 상태는 단일 인스턴스의 인메모리에 있다.** 방마다 `Sinks.Many` 하나로 브로드캐스트하고,
  Redis 에는 방 레지스트리와 게스트 토큰만 둔다. 틱마다 네트워크 왕복이 없다.
- **수평 확장이 지금은 불가능하다.** 인스턴스를 늘리려면 (a) 방 소유 인스턴스를 Redis 에 기록하고
  (b) 다른 인스턴스에 붙은 클라이언트에게 Redis Pub/Sub 으로 중계하거나, 로드밸런서에서 roomId
  기준 스티키 라우팅을 해야 한다. 지금은 그 값을 치르고 얻을 것이 없어 하지 않는다.
- 프로세스가 죽으면 진행 중인 판이 사라진다. 종료된 게임만 영속화하므로 손실 범위는 진행 중인
  판으로 한정된다.
```

- [ ] **Step 6: Update `docs/game/PRD.md`**

Replace the whole file with the state after this plan. Keep the AC table rows this plan covers marked with their test class, and leave later rows pointing at Plans 2–4.

```markdown
# PRD — game (게임)

`game` 도메인은 방을 만들고 초대 링크로 사람을 모아 실시간으로 겨루는 기능을 담당한다.

- 베이스 경로: `/api/game`, WebSocket `/ws/game`
- 코드: `com.woobeee.game` (`app-webflux`)
- 설계: [`../superpowers/specs/2026-08-01-game-omok-dodge-design.md`](../superpowers/specs/2026-08-01-game-omok-dodge-design.md)
- 전역 맥락: [`../_global/PRD.md`](../_global/PRD.md)

## 목표

- 회원이 방을 만들고 초대 링크로 사람을 모은다.
- 회원과 비회원(닉네임만 입력)이 같은 방에서 함께 플레이한다.
- 오목(1:1)과 장애물피하기(최대 8인)를 제공한다.
- 종료된 게임의 결과를 남기고 기보를 다시 본다.

## 참가자 모델

- `GameParticipant(participantId, displayName, kind, memberId)` 하나로 회원과 게스트를 다룬다.
- `participantId` 는 회원 `m:{memberId}`, 게스트 `g:{uuid}`.
- 회원은 auth 가 발급한 access token 을 공유 Redis 로 검증한다. 닉네임은 `members` 를 R2DBC 로
  읽어 온다 — **`members` 쓰기 소유권은 app-mvc 단독이고 game 은 읽기만 한다.**
- 게스트 토큰은 game 도메인이 발급해 Redis 에 6시간 TTL 로 둔다. auth 와 core 토큰 계약은
  건드리지 않는다.

## 핵심 기능 (엔드포인트)

| 기능 | 메서드 · 경로 | 인증 |
| --- | --- | --- |
| health | `GET /api/game/health` | 공개 |
| 내 토큰 확인 | `GET /api/game/me` | 회원 |
| 방 생성 | `POST /api/game/rooms` | 회원 |
| 방 요약 | `GET /api/game/rooms/{roomId}?invite=` | 공개 |
| 게스트 토큰 발급 | `POST /api/game/rooms/{roomId}/guest-tokens` | 공개 |
| 실시간 | `WS /ws/game` | 첫 JOIN 메시지의 토큰 |

## 방 규칙

| 항목 | 오목 | 장애물피하기 |
| --- | --- | --- |
| 정원 | 2 | 8 |
| 시작 조건 | 2명 모두 READY | 2명 이상 모두 READY |

- 방 TTL 6시간. 참가자가 0이 되면 즉시 소멸.
- 소켓이 끊기면 `DISCONNECTED` 로 두고 30초 유예. 유예 안에 재접속하면 자리를 잇는다.
- 명시적 `LEAVE` 는 유예 없이 즉시 이탈.
- 게임 시작 후 새 참가자는 받지 않는다. 재접속은 새 참가가 아니므로 허용된다.
- 방장이 빠지면 참가 순서상 다음 사람이 방장이 된다.

## WebSocket 프로토콜

봉투는 `{type, seq, payload}`. 서버 응답에는 `ackSeq` 가 실린다.

- 클라이언트 → 서버: `JOIN` `LEAVE` `READY` `START` `OMOK_PLACE` `DODGE_MOVE`
- 서버 → 클라이언트: `ROOM_STATE` `GAME_START` `OMOK_MOVED` `OMOK_REJECTED` `DODGE_TICK`
  `GAME_END` `ERROR`

## 인수 기준 (Acceptance Criteria)

각 항목은 테스트로 커버한다(프로세스 규칙은 `CLAUDE.md`). 동작/계약 변경 시 이 표를 먼저 갱신하고
테스트를 함께 수정한다.

| ID | 인수 기준 (Given–When–Then) | 커버 테스트 |
| --- | --- | --- |
| GAME-AC-01 | 방 생성은 회원만 가능하고 `roomId` 와 `inviteCode` 를 발급한다 | `RoomControllerTest` |
| GAME-AC-02 | `inviteCode` 가 틀리면 방 요약과 게스트 토큰 발급이 `403` 을 반환한다 | `RoomServiceTest`, `RoomControllerTest` |
| GAME-AC-03 | 게스트 토큰 발급은 닉네임을 요구하고, 같은 방에 중복 닉네임이면 `409` 를 반환한다 | `GuestIdentityServiceTest` |
| GAME-AC-04 | 정원이 찬 방에 `JOIN` 하면 거절한다 (오목 2, 장애물 8) | `RoomServiceTest` |
| GAME-AC-05 | 게임이 `IN_PROGRESS` 면 새 참가자의 `JOIN` 은 거절하고, 기존 참가자의 재접속은 허용한다 | `RoomServiceTest` |
| GAME-AC-06 | 방장이 이탈하면 다음 참가자가 방장이 되고, 참가자가 0이면 방이 소멸한다 | `RoomServiceTest` |
| GAME-AC-07 | `JOIN` 없이 10초가 지난 WebSocket 세션은 서버가 닫는다 | `GameWebSocketHandlerTest` |
| GAME-AC-08 | 연결이 끊기면 `DISCONNECTED` 로 두고 30초 안에 재접속하면 자리를 잇는다. 유예를 넘기면 이탈이 확정된다 | `RoomServiceTest`, `GameWebSocketHandlerTest` |
| GAME-AC-09 | 명시적 `LEAVE` 는 유예 없이 즉시 이탈로 처리한다 | `RoomServiceTest`, `GameWebSocketHandlerTest` |
| GAME-AC-10 | 흑의 삼삼·사사·장목 착수는 `OMOK_REJECTED` 로 거절하고 판 상태를 바꾸지 않는다 | 미작성 — Plan 2 |
| GAME-AC-11 | 백은 금수가 없고 6목 이상으로도 승리한다 | 미작성 — Plan 2 |
| GAME-AC-12 | 흑은 정확히 5목일 때만 승리한다 | 미작성 — Plan 2 |
| GAME-AC-13 | 열린 삼 판정은 열린 사를 만드는 자리가 금수면 열린 삼으로 보지 않는다 | 미작성 — Plan 2 |
| GAME-AC-14 | 차례가 아닌 참가자의 착수와 이미 놓인 자리 착수는 거절한다 | 미작성 — Plan 2 |
| GAME-AC-15 | 수당 제한시간을 넘기면 그 참가자가 패한다 | 미작성 — Plan 2 |
| GAME-AC-16 | 틱당 입력은 참가자별 1회만 반영하고, 격자 밖 이동은 무시한다 | 미작성 — Plan 3 |
| GAME-AC-17 | 참가자와 장애물이 서로 지나친 경우(스왑)도 충돌로 판정한다 | 미작성 — Plan 3 |
| GAME-AC-18 | 탈락 역순이 순위이고, 같은 틱 탈락은 공동 순위다 | 미작성 — Plan 3 |
| GAME-AC-19 | 같은 시드와 같은 입력 로그로 재생하면 원본과 같은 결과가 나온다 | 미작성 — Plan 3 |
| GAME-AC-20 | 게임이 끝나면 결과 1행과 참가자 행들을 기록하고 기보를 업로드한다 | 미작성 — Plan 2 |
| GAME-AC-21 | 기보 업로드가 실패해도 결과는 남고 `replay_object_key` 는 `null` 이다 | 미작성 — Plan 2 |
| GAME-AC-22 | 기보 다시보기는 그 게임 참가자 본인에게만 presigned URL을 발급한다 | 미작성 — Plan 2 |

## 비기능 요구사항

- 실시간 통신은 WebSocket([`../_global/adr/ADR-005-realtime-websocket.md`](../_global/adr/ADR-005-realtime-websocket.md)).
- **블로킹 호출 금지.** Redis 는 `ReactiveStringRedisTemplate`, DB 는 R2DBC.
- 게임 상태는 단일 인스턴스 인메모리다. 수평 확장은 ADR-005 의 후속 과제다.
```

- [ ] **Step 7: Update `CLAUDE.md`**

In the API endpoint table, replace the game row with:

```markdown
| app-webflux | game | `/api/game`, `/ws/game` | `health`(공개), `me`, `rooms*`(방 생성·요약·게스트 토큰), WebSocket 실시간 |
```

In the 후속 과제 table, replace the `게임 도메인 설계` row with:

```markdown
| 게임 규칙 구현 | 방·실시간 기반은 완료. 오목/장애물피하기 로직과 `V2__game.sql` 은 Plan 2·3 |
```

- [ ] **Step 8: Run the full verification set**

```bash
./mvnw -pl core,app-mvc,app-webflux -am test
cd front && npm run build && npx tsc --noEmit && cd ..
./mvnw -pl core dependency:tree | grep -E "starter-webmvc|starter-webflux|tomcat-embed|reactor-netty" && echo "FAIL: web stack leaked into core" || echo "OK"
./mvnw -pl app-webflux dependency:tree \
  | grep -E "spring-boot-starter-jdbc|spring-boot-starter-data-jpa|org\.postgresql:postgresql:|awssdk:apache-client" \
  && echo "FAIL: blocking client leaked into app-webflux" || echo "OK"
```

Expected: BUILD SUCCESS, front compiles, both dependency checks print `OK`.

- [ ] **Step 9: Commit**

```bash
git add app-webflux/src/main/java/com/woobeee/game/room docs/_global/adr/ADR-005-realtime-websocket.md docs/game/PRD.md CLAUDE.md app-webflux/src/test/java/com/woobeee/game/room/RoomSweeperTest.java
git commit -m "feat(game): sweep expired rooms; document realtime ADR and game PRD"
```

---

## Done when

- A member creates a room, gets an invite link, and a guest joins it with a nickname.
- Both sockets receive `ROOM_STATE` on every join, ready toggle, disconnect, and leave.
- Killing one browser tab flips that participant to `DISCONNECTED`; reopening within 30 seconds restores the seat; waiting longer removes it.
- GAME-AC-01 through GAME-AC-09 are green.
