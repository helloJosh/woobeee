package com.woobeee.game.ws;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.identity.GuestIdentityService;
import com.woobeee.game.identity.MemberReader;
import com.woobeee.game.security.ReactiveTokenVerifier;
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
        return GameErrorCode.INVALID_GAME_TOKEN.asException();
    }
}
