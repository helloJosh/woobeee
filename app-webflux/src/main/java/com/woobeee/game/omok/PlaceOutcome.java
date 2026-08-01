package com.woobeee.game.omok;

import java.time.Instant;

public record PlaceOutcome(
        Status status,
        String reason,
        Stone stone,
        String winnerParticipantId,
        Instant turnDeadline
) {
    public enum Status {
        PLACED,
        REJECTED,
        WIN
    }

    static PlaceOutcome rejected(String reason) {
        return new PlaceOutcome(Status.REJECTED, reason, null, null, null);
    }

    static PlaceOutcome placed(Stone stone, Instant turnDeadline) {
        return new PlaceOutcome(Status.PLACED, null, stone, null, turnDeadline);
    }

    static PlaceOutcome win(Stone stone, String winnerParticipantId, String reason) {
        return new PlaceOutcome(Status.WIN, reason, stone, winnerParticipantId, null);
    }
}
