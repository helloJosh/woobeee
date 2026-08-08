package com.woobeee.game.omok;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OmokReplayWriter {
    private final ObjectMapper objectMapper;

    public OmokReplayWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toNdjson(OmokGame game, Map<String, String> displayNames) {
        StringBuilder builder = new StringBuilder();

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("v", 1);
        header.put("gameType", "OMOK");
        header.put("boardSize", OmokBoard.SIZE);
        header.put("players", List.of(
                player(game.blackParticipantId(), Stone.BLACK, displayNames),
                player(game.whiteParticipantId(), Stone.WHITE, displayNames)
        ));
        builder.append(write(header)).append('\n');

        for (OmokMove move : game.moves()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("t", move.index());
            line.put("p", move.participantId());
            line.put("x", move.x());
            line.put("y", move.y());
            builder.append(write(line)).append('\n');
        }

        return builder.toString();
    }

    private Map<String, Object> player(String participantId, Stone color, Map<String, String> displayNames) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("participantId", participantId);
        player.put("color", color.name());
        player.put("displayName", displayNames.getOrDefault(participantId, participantId));
        return player;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialise omok replay", exception);
        }
    }
}
