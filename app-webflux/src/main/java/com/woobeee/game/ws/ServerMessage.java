package com.woobeee.game.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerMessage(
        String type,
        Long ackSeq,
        Object payload
) {
    public static ServerMessage of(String type, Object payload) {
        return new ServerMessage(type, null, payload);
    }

    public static ServerMessage ack(String type, Long ackSeq, Object payload) {
        return new ServerMessage(type, ackSeq, payload);
    }
}
