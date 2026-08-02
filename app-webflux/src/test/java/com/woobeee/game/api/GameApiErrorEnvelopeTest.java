package com.woobeee.game.api;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import com.woobeee.game.room.RoomStatus;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GAME-AC-26 — 게임 API 의 실패 응답은 app-mvc 와 같은 {@code ApiResponse} 봉투로 나가고,
 * 게스트가 실제로 마주치는 경우마다 서로 다른 코드를 싣는다.
 *
 * <p>{@code front/lib/api.ts} 는 {@code header.isSuccessful === false} 일 때
 * {@code header.message} 를 코드로 읽어 {@code front/lib/errors/error-messages.ts} 에서
 * 한국어 문구를 찾는다. 그래서 여기서 검사하는 것은 상태 코드가 아니라 <b>본문</b>이다 —
 * 상태만 맞고 코드가 뭉개지면 화면에는 "예기치 못한 오류가 발생했습니다." 하나만 뜬다.
 *
 * <p>어드바이스를 {@code @Import} 하지 않는 것은 의도적이다. {@code @WebFluxTest} 슬라이스는
 * {@code @ControllerAdvice} 를 자동으로 포함하므로, 이 테스트가 통과한다는 것은 곧 실제
 * 애플리케이션에서도 같은 봉투가 나간다는 뜻이다.
 */
@WebFluxTest(RoomController.class)
@Import({GameAuthWebFilter.class, GameApiErrorEnvelopeTest.Beans.class})
class GameApiErrorEnvelopeTest {

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

        @Bean
        @SuppressWarnings("unchecked")
        ReactiveStringRedisTemplate reactiveStringRedisTemplate() {
            ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
            ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
            when(redis.<String, String>opsForHash()).thenReturn(hash);
            when(hash.putAll(anyString(), any())).thenReturn(Mono.just(true));
            when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
            return redis;
        }

        /** 실제 구현을 쓴다 — 코드가 어디서 붙는지까지 통째로 검사하려는 것이다. */
        @Bean
        GuestIdentityService guestIdentityService(
                ReactiveStringRedisTemplate redis,
                RoomService roomService,
                GameIdGenerator ids
        ) {
            return new GuestIdentityService(redis, roomService, ids);
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RoomService roomService;

    @MockitoBean
    private ReactiveTokenVerifier reactiveTokenVerifier;

    @MockitoBean
    private MemberReader memberReader;

    @BeforeEach
    void setUp() {
        when(reactiveTokenVerifier.verify(eq("tok-1")))
                .thenReturn(Mono.just(new TokenMetadata(11L, "ROLE_MEMBER", "web", "127.0.0.1")));
        when(memberReader.findNickname(11L)).thenReturn(Mono.just("host"));
    }

    private void expectFailure(WebTestClient.ResponseSpec response, int status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.header.isSuccessful").isEqualTo(false)
                .jsonPath("$.header.resultCode").isEqualTo(status)
                .jsonPath("$.header.message").isEqualTo(code);
    }

    /** GAME-AC-26 */
    @Test
    void aWrongInviteCodeCarriesItsOwnCode() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        expectFailure(
                webTestClient.get().uri("/api/game/rooms/room-1?invite=wrong").exchange(),
                403,
                "game_invalidInviteCode"
        );
    }

    /** GAME-AC-26 */
    @Test
    void anUnknownRoomCarriesItsOwnCode() {
        expectFailure(
                webTestClient.get().uri("/api/game/rooms/nope?invite=code").exchange(),
                404,
                "game_roomNotFound"
        );
    }

    /** GAME-AC-26 */
    @Test
    void aNicknameAlreadyUsedInTheRoomCarriesItsOwnCode() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        expectFailure(
                webTestClient.post().uri("/api/game/rooms/room-1/guest-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"inviteCode\":\"code\",\"nickname\":\"host\"}")
                        .exchange(),
                409,
                "game_nicknameTaken"
        );
    }

    /** GAME-AC-26, GAME-AC-27 */
    @Test
    void aFullRoomCarriesItsOwnCode() {
        Room room = roomService.create(GameType.OMOK, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.member(12L, "other"));

        expectFailure(
                webTestClient.post().uri("/api/game/rooms/room-1/guest-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"inviteCode\":\"code\",\"nickname\":\"손님\"}")
                        .exchange(),
                409,
                "game_roomFull"
        );
    }

    /** GAME-AC-26, GAME-AC-27 */
    @Test
    void aGameAlreadyInProgressCarriesItsOwnCode() {
        Room room = roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));
        room.setStatus(RoomStatus.IN_PROGRESS);

        expectFailure(
                webTestClient.post().uri("/api/game/rooms/room-1/guest-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"inviteCode\":\"code\",\"nickname\":\"손님\"}")
                        .exchange(),
                409,
                "game_gameAlreadyStarted"
        );
    }

    /** GAME-AC-26 */
    @Test
    void aMissingAccessTokenCarriesItsOwnCode() {
        expectFailure(
                webTestClient.post().uri("/api/game/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"gameType\":\"OMOK\"}")
                        .exchange(),
                401,
                "game_unauthorized"
        );
    }

    /** GAME-AC-26 — bean validation 실패도 같은 봉투로 나간다. */
    @Test
    void aRejectedRequestBodyStillComesBackInTheEnvelope() {
        roomService.create(GameType.DODGE, GameParticipant.member(11L, "host"));

        expectFailure(
                webTestClient.post().uri("/api/game/rooms/room-1/guest-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{\"inviteCode\":\"code\",\"nickname\":\"\"}")
                        .exchange(),
                400,
                "game_badRequest"
        );
    }

    /**
     * GAME-AC-26 — 예상 못 한 예외는 500 + 단일 코드로 뭉개고 내부 사정을 한 글자도 흘리지
     * 않는다. 여기서 흘리기 쉬운 것은 예외 메시지다(연결 문자열·자격증명이 흔히 들어간다).
     */
    @Test
    void anUnexpectedFailureLeaksNothing() {
        when(memberReader.findNickname(11L))
                .thenReturn(Mono.error(new IllegalStateException("r2dbc://root:hunter2@db:9432/market is down")));

        byte[] body = webTestClient.post().uri("/api/game/rooms")
                .header("Authorization", "Bearer tok-1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"gameType\":\"OMOK\"}")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.header.isSuccessful").isEqualTo(false)
                .jsonPath("$.header.resultCode").isEqualTo(500)
                .jsonPath("$.header.message").isEqualTo("game_unexpected")
                .returnResult()
                .getResponseBodyContent();

        String text = new String(body == null ? new byte[0] : body, StandardCharsets.UTF_8);
        assertThat(text).doesNotContain("hunter2");
        assertThat(text).doesNotContain("IllegalStateException");
        assertThat(text).doesNotContain("com.woobeee");
    }
}
