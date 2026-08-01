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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
