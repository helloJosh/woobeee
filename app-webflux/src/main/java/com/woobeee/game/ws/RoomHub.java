package com.woobeee.game.ws;

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
 */
@Component
public class RoomHub {
    private final Map<String, Sinks.Many<ServerMessage>> sinks = new ConcurrentHashMap<>();

    public Flux<ServerMessage> subscribe(String roomId) {
        return sinkFor(roomId).asFlux();
    }

    public void broadcast(String roomId, ServerMessage message) {
        Sinks.Many<ServerMessage> sink = sinks.get(roomId);
        if (sink != null) {
            sink.tryEmitNext(message);
        }
    }

    public void close(String roomId) {
        Sinks.Many<ServerMessage> sink = sinks.remove(roomId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    private Sinks.Many<ServerMessage> sinkFor(String roomId) {
        return sinks.computeIfAbsent(
                roomId,
                key -> Sinks.many().multicast().onBackpressureBuffer(256, false)
        );
    }
}
