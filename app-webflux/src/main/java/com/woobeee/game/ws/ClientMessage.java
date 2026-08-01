package com.woobeee.game.ws;

import tools.jackson.databind.JsonNode;

public record ClientMessage(
        String type,
        Long seq,
        JsonNode payload
) {
}
