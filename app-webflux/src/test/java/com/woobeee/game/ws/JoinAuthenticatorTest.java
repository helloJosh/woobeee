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
