package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;

public final class RoomMember {
    private final GameParticipant participant;
    private boolean ready;
    private ConnectionState connection;

    RoomMember(GameParticipant participant) {
        this.participant = participant;
        this.ready = false;
        this.connection = ConnectionState.CONNECTED;
    }

    public GameParticipant participant() {
        return participant;
    }

    public boolean ready() {
        return ready;
    }

    public ConnectionState connection() {
        return connection;
    }

    void ready(boolean ready) {
        this.ready = ready;
    }

    void connection(ConnectionState connection) {
        this.connection = connection;
    }
}
