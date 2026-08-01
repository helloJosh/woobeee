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
        // "host" is already taken in this room. If the duplicate-nickname check ran before
        // the invite-code check, this would return 409 instead of 403 — which would leak to
        // the caller that the nickname is taken despite the wrong invite code.
        assertThatThrownBy(() -> service.issue("room-1", "wrong", "host").block())
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
