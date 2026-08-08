package com.woobeee.game.result;

import java.time.Instant;
import java.util.List;

public record FinishedGame(
        String gameType,
        String roomId,
        Instant startedAt,
        Instant endedAt,
        String winnerParticipantId,
        List<FinishedParticipant> participants
) {
}
