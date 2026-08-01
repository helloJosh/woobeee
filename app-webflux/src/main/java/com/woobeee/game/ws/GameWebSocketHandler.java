package com.woobeee.game.ws;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
                .map(WebSocketMessage::getPayloadAsText)
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

        String roomId = payload.path("roomId").asString(null);
        String inviteCode = payload.path("inviteCode").asString(null);
        String token = payload.path("token").asString(null);
        if (roomId == null || inviteCode == null || token == null) {
            return session.close();
        }

        return joinAuthenticator.authenticate(roomId, token)
                .flatMap(participant -> {
                    // 토큰 인증은 여기서 끝났을 뿐이다 — 방 존재, 초대 코드, 정원, 진행 상태는
                    // dispatcher.join 안에서 검증된다. 그 검증이 끝나기 전에 이 세션을 "참가함"
                    // 상태로 만들거나 허브에 구독시키면(C2), 유효한 토큰이지만 틀린 초대 코드를
                    // 댄 참가자가 다른 참가자들의 ROOM_STATE 를 계속 엿듣게 된다 — join 이
                    // 실패해도 state 가 non-null 이라 JOIN 데드라인도 더 이상 이 세션을 닫지
                    // 않는다. 그래서 성공/실패를 boolean 으로 돌려받아, 실패하면 아무것도
                    // 구독시키지 않고 세션을 닫는다.
                    //
                    // 구독은 onValidated 콜백으로 넘긴다 — dispatcher.join 이 참가를 확정한
                    // 직후, 그 참가에 대한 ROOM_STATE 를 브로드캐스트하기 직전에 불리므로
                    // 이 세션도 자신의 참가로 인한 ROOM_STATE 를 받는다.
                    boolean joined = dispatcher.join(roomId, inviteCode, participant, () -> {
                        state.set(new SessionState(roomId, participant.participantId()));
                        joinedRoomId.tryEmitValue(roomId);
                    });
                    return joined ? Mono.<Void>empty() : session.close();
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
