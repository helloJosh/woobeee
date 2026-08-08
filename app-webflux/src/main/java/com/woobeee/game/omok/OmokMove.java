package com.woobeee.game.omok;

public record OmokMove(
        int index,
        String participantId,
        int x,
        int y,
        Stone stone
) {
}
