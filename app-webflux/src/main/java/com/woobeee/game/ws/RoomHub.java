package com.woobeee.game.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 방마다 하나씩 두는 브로드캐스트 채널.
 *
 * <p>multicast + onBackpressureBuffer 를 쓴다. 느린 구독자 하나가 방 전체를 막지 않도록
 * 버퍼가 차면 그 구독자만 끊긴다.
 *
 * <p><b>emit 은 방마다 직렬화한다.</b> {@code Sinks.many().multicast()} 로 만든 sink 는
 * 자체적으로 직렬화되지 않는다 — 서로 다른 Netty 이벤트 루프 스레드 둘이 동시에 같은 방에
 * {@code tryEmitNext} 를 부르면 한쪽이 {@code FAIL_NON_SERIALIZED} 를 받는다. 두 플레이어가
 * 각자 다른 스레드에서 낸 유효한 {@code OMOK_MOVED} 와 상대의 {@code OMOK_REJECTED} 가 경합하는
 * 상황이 실전에서 그대로 일어난다. 그래서 같은 sink 인스턴스를 락으로 써서 emit(성공이든
 * 실패든)을 방마다 한 번에 하나씩만 진행한다 — 버퍼가 정말로 가득 차서 나는 {@code FAIL_OVERFLOW}
 * 는 여전히 그대로 로그를 남기고 그 결과를 반환한다.
 */
@Component
public class RoomHub {
    private static final Logger log = LoggerFactory.getLogger(RoomHub.class);
    private final Map<String, Sinks.Many<ServerMessage>> sinks = new ConcurrentHashMap<>();

    public Flux<ServerMessage> subscribe(String roomId) {
        return sinkFor(roomId).asFlux();
    }

    /**
     * Broadcast a message to all subscribers of a room.
     *
     * <p>Returns {@link Sinks.EmitResult#OK} if the message was accepted by the sink,
     * or another result if the emit failed (overflow, terminated, etc.).
     * Note: {@code OK} also covers the case where the room has no sink at all,
     * so nothing was actually sent; check the room existence separately if needed.
     */
    public Sinks.EmitResult broadcast(String roomId, ServerMessage message) {
        Sinks.Many<ServerMessage> sink = sinks.get(roomId);
        if (sink != null) {
            Sinks.EmitResult result;
            // 같은 sink 인스턴스를 락으로 써서 이 방에 대한 emit 을 직렬화한다 — 동시에 들어온
            // 다른 스레드의 emit 은 이 블록이 끝날 때까지 기다리므로 FAIL_NON_SERIALIZED 가 나지
            // 않는다. 버퍼가 실제로 가득 찬 FAIL_OVERFLOW 는 그대로 관찰되고 로그·반환된다.
            synchronized (sink) {
                result = sink.tryEmitNext(message);
            }
            if (result != Sinks.EmitResult.OK) {
                log.warn("Broadcast to room {} failed with type={} result={}",
                    roomId, message.type(), result);
            }
            return result;
        }
        return Sinks.EmitResult.OK;
    }

    public void close(String roomId) {
        Sinks.Many<ServerMessage> sink = sinks.remove(roomId);
        if (sink != null) {
            // onComplete 도 onNext 와 같은 sink 에 대한 emission 이므로 같은 락으로 직렬화한다.
            synchronized (sink) {
                sink.tryEmitComplete();
            }
        }
    }

    private Sinks.Many<ServerMessage> sinkFor(String roomId) {
        return sinks.computeIfAbsent(
                roomId,
                key -> Sinks.many().multicast().onBackpressureBuffer(256, false)
        );
    }
}
