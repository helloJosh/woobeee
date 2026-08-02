package com.woobeee.game.ws;

import com.woobeee.game.api.error.GameErrorCode;
import com.woobeee.game.ws.payload.ErrorPayload;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Optional;
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
                    // 않는다. 그래서 거절 이유를 Optional 로 돌려받아, 거절이면 아무것도
                    // 구독시키지 않고 이유를 써 준 뒤 세션을 닫는다.
                    //
                    // 구독은 onValidated 콜백으로 넘긴다 — dispatcher.join 이 참가를 확정한
                    // 직후, 그 참가에 대한 ROOM_STATE 를 브로드캐스트하기 직전에 불리므로
                    // 이 세션도 자신의 참가로 인한 ROOM_STATE 를 받는다.
                    Optional<GameErrorCode> rejection =
                            dispatcher.join(roomId, inviteCode, participant, () -> {
                                state.set(new SessionState(roomId, participant.participantId()));
                                joinedRoomId.tryEmitValue(roomId);
                            });
                    return rejection
                            .map(errorCode -> rejectWithReason(session, errorCode))
                            .orElseGet(Mono::empty);
                })
                .onErrorResume(error -> rejectWithReason(session, codeOf(error)))
                .then();
    }

    private static GameErrorCode codeOf(Throwable error) {
        return error instanceof ResponseStatusException statusException
                ? GameErrorCode.of(statusException)
                : GameErrorCode.UNEXPECTED;
    }

    /**
     * 거절 이유를 알린 뒤 세션을 닫는다. 참가가 막히는 두 갈래 — 토큰 인증 실패와, 토큰은
     * 멀쩡한데 방이 거절한 경우(틀린 초대 코드·정원 초과·이미 시작) — 가 모두 여기로 온다.
     *
     * <p>이 세션은 아직 방 허브를 구독하지 않았다 — 구독은 {@code dispatcher.join} 이 통과한
     * 뒤에야 일어난다. 그래서 허브로 나가는 ERROR 브로드캐스트는 이 세션에 절대 닿지 않는다.
     * 프레임을 세션에 <b>직접</b> 써야 하는 것은 그래서다. 이게 없으면 소켓이 조용히 닫히기만
     * 하고 화면은 왜 거절됐는지 말해 줄 방법이 없다. 방이 거절한 쪽은 예전에 더 나빴다 —
     * 그 ERROR 가 방 허브로 나가 <b>다른 참가자들만</b> 남의 실패를 받아 봤다. 그래서
     * {@code dispatcher.join} 은 이제 방에 알리지 않고 이유만 돌려준다.
     *
     * <p>여기서 {@code session.send} 를 한 번 더 부르는 것이 바깥의 outbound 스트림과 겹치지
     * 않는 이유: outbound 는 {@code joinedRoomId} 가 값을 낼 때까지 아무것도 쓰지 않고, 그 값은
     * {@code onValidated} 안에서만 나온다. 그리고 이 메서드에 닿는 두 경로 모두 그 콜백보다
     * <b>앞에서</b> 끝난다 — 토큰 인증 실패는 {@code dispatcher.join} 을 부르기도 전이고,
     * 방의 거절은 {@code roomService.join} 이 던진 것이라 {@code onValidated} 까지 가지 못한다.
     * 입장이 확정된 뒤의 실패는 애초에 거절로 돌아오지 않는다 —
     * {@code RoomCommandDispatcher.join} 이 그 경우를 빈 Optional 로 바꾸고 방으로 흘려보낸다.
     * 그 분리가 여기서 writer 가 하나뿐임을 보장한다(그래서 그쪽을 바꾸면 이 불변식이 깨진다.
     * {@code RoomCommandDispatcherTest.aFailureAfterAdmissionIsNotAJoinRejectionAndGoesToTheRoomInstead}
     * 가 그 분리를 고정한다).
     */
    private Mono<Void> rejectWithReason(WebSocketSession session, GameErrorCode errorCode) {
        String frame = toTextMessage(ServerMessage.of("ERROR", ErrorPayload.of(errorCode)));
        return session.send(Mono.just(session.textMessage(frame)))
                .onErrorResume(ignored -> Mono.empty())
                .then(session.close())
                .then(Mono.empty());
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
            // 마지막 방어선. 직렬화가 깨져도 클라이언트가 아는 모양·아는 코드로 나가야 한다.
            return "{\"type\":\"ERROR\",\"payload\":{\"status\":500,\"code\":\""
                    + GameErrorCode.UNEXPECTED.code() + "\",\"message\":\"serialization failed\"}}";
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
