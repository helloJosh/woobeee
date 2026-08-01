package com.woobeee.game.api.response;

public record GameResultSummaryResponse(
        long gameResultId,
        String gameType,
        String endedAt,
        int finishRank,
        String winnerDisplayName,
        boolean replayAvailable
) {
}
