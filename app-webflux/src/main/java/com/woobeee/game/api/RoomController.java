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
import com.woobeee.game.security.GamePrincipal;
import com.woobeee.game.security.GamePrincipals;
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
        GamePrincipal principal = GamePrincipals.require(exchange);

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
}
