package com.woobeee.game.omok;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OmokReplayWriterTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void writesAHeaderLineThenOneLinePerMove() throws Exception {
        OmokGame game = new OmokGame("m:11", "g:a", START, Duration.ofSeconds(60));
        game.place("m:11", 7, 7, START);
        game.place("g:a", 7, 8, START);

        String ndjson = new OmokReplayWriter(new ObjectMapper())
                .toNdjson(game, Map.of("m:11", "host", "g:a", "손님"));

        String[] lines = ndjson.strip().split("\n");
        assertThat(lines).hasSize(3);

        ObjectMapper mapper = new ObjectMapper();
        var header = mapper.readTree(lines[0]);
        assertThat(header.get("v").asInt()).isEqualTo(1);
        assertThat(header.get("gameType").asText()).isEqualTo("OMOK");
        assertThat(header.get("boardSize").asInt()).isEqualTo(15);
        assertThat(header.get("players")).hasSize(2);
        assertThat(header.get("players").get(0).get("participantId").asText()).isEqualTo("m:11");
        assertThat(header.get("players").get(0).get("color").asText()).isEqualTo("BLACK");
        assertThat(header.get("players").get(0).get("displayName").asText()).isEqualTo("host");
        assertThat(header.get("players").get(1).get("color").asText()).isEqualTo("WHITE");

        var first = mapper.readTree(lines[1]);
        assertThat(first.get("t").asInt()).isEqualTo(1);
        assertThat(first.get("p").asText()).isEqualTo("m:11");
        assertThat(first.get("x").asInt()).isEqualTo(7);
        assertThat(first.get("y").asInt()).isEqualTo(7);

        var second = mapper.readTree(lines[2]);
        assertThat(second.get("t").asInt()).isEqualTo(2);
        assertThat(second.get("p").asText()).isEqualTo("g:a");
    }

    @Test
    void writesOnlyTheHeaderWhenNoMoveWasPlayed() {
        OmokGame game = new OmokGame("m:11", "g:a", START, Duration.ofSeconds(60));

        String ndjson = new OmokReplayWriter(new ObjectMapper())
                .toNdjson(game, Map.of("m:11", "host", "g:a", "손님"));

        assertThat(ndjson.strip().split("\n")).hasSize(1);
    }
}
