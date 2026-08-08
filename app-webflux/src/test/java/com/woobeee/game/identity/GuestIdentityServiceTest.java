package com.woobeee.game.identity;

import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.room.RoomStatus;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuestIdentityServiceTest {
    /** 무한 대기하는 StepVerifier 가 한 번 이 스위트를 매달아 놓은 적이 있다. 모두 시간을 건다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

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
                .expectComplete()
                .verify(TIMEOUT);

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
        assertThatThrownBy(() -> service.issue("room-1", "code", " ").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** GAME-AC-03 */
    @Test
    void rejectsANicknameAlreadyUsedInTheSameRoom() {
        assertThatThrownBy(() -> service.issue("room-1", "code", "host").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** GAME-AC-02 — the invite code is checked before anything is issued. */
    @Test
    void rejectsAWrongInviteCode() {
        // "host" is already taken in this room. If the duplicate-nickname check ran before
        // the invite-code check, this would return 409 instead of 403 — which would leak to
        // the caller that the nickname is taken despite the wrong invite code.
        assertThatThrownBy(() -> service.issue("room-1", "wrong", "host").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * GAME-AC-27 — 정원이 찬 방에는 토큰을 아예 만들지 않는다. 여기서 통과시키면 프론트는
     * 플레이 화면으로 넘어가고, 거절은 WebSocket JOIN 에서야 나온다 — 이유를 보여줄 자리가
     * 없는 화면에서.
     */
    @Test
    void refusesATokenForAFullRoom() {
        Room full = roomService.create(GameType.OMOK, GameParticipant.member(11L, "host"));
        full.addMember(GameParticipant.member(12L, "other"));

        assertThatThrownBy(() -> service.issue("room-1", "code", "손님").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Room is full")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(hash, never()).putAll(anyString(), any());
    }

    /** GAME-AC-27 */
    @Test
    void refusesATokenWhenTheGameHasAlreadyStarted() {
        room.setStatus(RoomStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.issue("room-1", "code", "손님").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Game already started")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(hash, never()).putAll(anyString(), any());
    }

    /** GAME-AC-27 — 자리가 남은 WAITING 방은 그대로 발급된다. */
    @Test
    void stillIssuesForARoomWithSpaceThatHasNotStarted() {
        roomService.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        StepVerifier.create(service.issue("room-1", "code", "손님"))
                .assertNext(token -> assertThat(token.participantId()).isEqualTo("g:guest-1"))
                .expectComplete()
                .verify(TIMEOUT);
    }

    /**
     * GAME-AC-27 경계 — 재접속하는 게스트를 정원 검사로 내쫓지 않는다.
     *
     * <p>{@code issue} 는 호출될 때마다 새 {@code participantId} 를 만든다. 그러니 이 지점에서
     * "이미 이 방의 멤버"를 알아볼 수단은 닉네임뿐이고, 그 검사는 정원·상태 검사보다 <b>먼저</b>
     * 돈다. 결과적으로 정원이 찬 방이라도 이미 그 방에 있는 이름에게는 `Room is full` 이 아니라
     * `Nickname is already used in this room` 이 간다. 정원 검사를 앞으로 옮기면 이 테스트가
     * 깨진다 — 그때는 자기 방에서 쫓겨난 사람이 "방이 꽉 찼다" 는 말을 듣게 된다.
     *
     * <p>정상적인 재접속은 애초에 이 경로를 타지 않는다. 게스트 토큰은 6시간짜리라 재접속은
     * 저장해 둔 토큰으로 WebSocket JOIN 을 다시 하는 것이고, 거기서 {@code Room.admit} 이
     * {@code RECONNECTED} 를 돌려주며 정원·상태 검사를 건너뛴다.
     */
    @Test
    void someoneAlreadyInAFullRoomIsToldTheNicknameIsTakenNotThatTheRoomIsFull() {
        Room full = roomService.create(GameType.OMOK, GameParticipant.member(11L, "host"));
        full.addMember(GameParticipant.guest("guest-9", "손님"));

        assertThatThrownBy(() -> service.issue("room-1", "code", "손님").block(TIMEOUT))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nickname is already used in this room");
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
                .expectComplete()
                .verify(TIMEOUT);
    }

    @Test
    void verifyIsEmptyForAnUnknownToken() {
        when(hash.entries("game:guest:missing")).thenReturn(reactor.core.publisher.Flux.empty());

        StepVerifier.create(service.verify("missing")).expectComplete().verify(TIMEOUT);
    }
}
