package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.GameIdGenerator;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.RoomRegistry;
import com.woobeee.game.room.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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

        // 기본값: join 이 성공한 것으로 하고, 실제 dispatcher처럼 onValidated 콜백을 실행해
        // 세션이 "참가함" 상태가 되고 허브에 구독되게 한다. 실패 경로를 확인하는 테스트는
        // 이 스텁을 개별적으로 덮어써서 false 를 돌려주고 콜백을 실행하지 않는다(C2).
        stubJoinSucceeds();
    }

    private void stubJoinSucceeds() {
        when(dispatcher.join(anyString(), anyString(), any(GameParticipant.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    Runnable onValidated = invocation.getArgument(3);
                    onValidated.run();
                    return true;
                });
    }

    private void stubJoinFails() {
        when(dispatcher.join(anyString(), anyString(), any(GameParticipant.class), any(Runnable.class)))
                .thenReturn(false);
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
        verify(dispatcher).join(eq("room-1"), eq("code"), any(GameParticipant.class), any(Runnable.class));
    }

    /**
     * 보안 경계: 인증 실패(알 수 없는 토큰, 다른 방의 게스트 토큰, 사라진 회원 등 모두
     * JoinAuthenticator 가 UNAUTHORIZED 로 던진다)면 세션을 닫아야 한다. 닫되 방에는
     * 절대 join 시키면 안 된다 — 닫고 나서도 join 이 불렸다면 유령 참가자가 방 상태에
     * 남는다.
     */
    @Test
    void closesTheSessionWhenJoinAuthenticationFails() {
        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid game token")));
        WebSocketSession session = sessionEmitting(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"code\",\"token\":\"tok\"}}"
        );

        handler.handle(session).subscribe();

        verify(session).close();
        verify(dispatcher, never()).join(anyString(), anyString(), any(), any());
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

    /**
     * C2 regression: a member with a VALID token but a WRONG invite code must not be treated as
     * joined. Reproduces the reviewer's finding: the old handler set {@code state} and emitted
     * {@code joinedRoomId} (subscribing the session to the room hub) as soon as the token
     * verified, before {@code dispatcher.join} checked the invite code — whose failure {@code
     * guard} swallows into an ERROR broadcast. That let an outsider with a valid token but the
     * wrong invite code sit on the hub and receive every other participant's ROOM_STATE, and it
     * kept the JOIN deadline from ever closing the socket because {@code state} was non-null.
     *
     * <p>Uses the real {@link RoomCommandDispatcher}/{@link RoomService}/{@link RoomHub} (only
     * {@link JoinAuthenticator} is mocked) so the invite-code check is the real one, not a stub.
     */
    @Test
    void wrongInviteCodeClosesTheSessionWithoutSubscribingOrJoiningTheRoom() {
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
        RoomService roomService = new RoomService(registry);
        RoomHub realHub = spy(new RoomHub());
        RoomCommandDispatcher realDispatcher = new RoomCommandDispatcher(roomService, realHub, List.of());
        roomService.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        GameWebSocketHandler realHandler = new GameWebSocketHandler(
                authenticator,
                realDispatcher,
                realHub,
                new ObjectMapper(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                scheduler
        );

        when(authenticator.authenticate(eq("room-1"), eq("tok")))
                .thenReturn(Mono.just(GameParticipant.member(22L, "outsider")));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.receive()).thenReturn(Flux.just(
                "{\"type\":\"JOIN\",\"seq\":1,\"payload\":{\"roomId\":\"room-1\",\"inviteCode\":\"WRONG\",\"token\":\"tok\"}}"
        ).map(payload -> {
            WebSocketMessage message = mock(WebSocketMessage.class);
            when(message.getPayloadAsText()).thenReturn(payload);
            return message;
        }));
        when(session.textMessage(anyString())).thenReturn(mock(WebSocketMessage.class));
        // 실제로 넘어온 publisher를 구독해야 hub.subscribe 호출 여부를 신뢰성 있게 검증할 수 있다
        // (hubBroadcastsReachTheSessionAfterJoin과 같은 패턴).
        when(session.send(any())).thenAnswer(invocation -> {
            Publisher<WebSocketMessage> messages = invocation.getArgument(0);
            return Flux.from(messages).then();
        });
        when(session.close()).thenReturn(Mono.empty());

        realHandler.handle(session).subscribe();

        verify(session).close();
        verify(realHub, never()).subscribe(eq("room-1"));
        assertThat(roomService.findRoom("room-1").orElseThrow().members()).hasSize(1);

        // JOIN 데드라인이 여전히 살아 있어야 한다 — state가 null로 남아 있다는 증거다.
        scheduler.advanceTimeBy(Duration.ofSeconds(11));
        verify(session, times(2)).close();
    }

    @Test
    void messagesBeforeJoinAreIgnored() {
        WebSocketSession session = sessionEmitting("{\"type\":\"READY\",\"seq\":1,\"payload\":{\"ready\":true}}");

        handler.handle(session).subscribe();

        verify(dispatcher, never()).ready(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
