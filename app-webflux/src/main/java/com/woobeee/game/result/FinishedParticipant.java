package com.woobeee.game.result;

public record FinishedParticipant(
        String participantId,
        String displayName,
        Long memberId,
        int finishRank
) {
}
