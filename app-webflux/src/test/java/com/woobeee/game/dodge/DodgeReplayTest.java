package com.woobeee.game.dodge;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DodgeReplayTest {

    private static final String A = "m:11";
    private static final String B = "g:a";

    /** GAME-AC-19 — 같은 시드와 같은 입력이면 원본과 같은 결과가 나온다. */
    @Test
    void rerunningAReplayReproducesTheOriginalRanks() {
        int seed = 987654321;
        List<String> players = List.of(A, B, "g:c", "g:d");

        DodgeGame original = new DodgeGame(players, seed);
        Map<Integer, Map<String, Direction>> inputs = new LinkedHashMap<>();

        Direction[] cycle = {Direction.LEFT, Direction.RIGHT, Direction.UP, Direction.DOWN};
        int guard = 0;
        while (!original.finished() && guard < 2000) {
            Map<String, Direction> tickInputs = new LinkedHashMap<>();
            for (int i = 0; i < players.size(); i++) {
                if ((original.tick() + i) % 3 == 0) {
                    tickInputs.put(players.get(i), cycle[(original.tick() + i) % cycle.length]);
                }
            }
            if (!tickInputs.isEmpty()) {
                inputs.put(original.tick(), tickInputs);
            }
            original.advanceOneTick(tickInputs);
            guard++;
        }

        assertThat(original.finished()).isTrue();

        DodgeGame replayed = new DodgeReplayRunner()
                .rerun(new DodgeReplay(seed, players, inputs));

        assertThat(replayed.finalRanks()).isEqualTo(original.finalRanks());
        assertThat(replayed.eliminationOrder()).isEqualTo(original.eliminationOrder());
        assertThat(replayed.tick()).isEqualTo(original.tick());
    }

    @Test
    void ndjsonHasAHeaderThenOneLinePerTickWithInput() throws Exception {
        Map<Integer, Map<String, Direction>> inputs = new LinkedHashMap<>();
        inputs.put(3, Map.of(A, Direction.LEFT));
        inputs.put(7, Map.of(A, Direction.UP, B, Direction.RIGHT));

        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(8412739, List.of(A, B), inputs),
                Map.of(A, "host", B, "손님")
        );

        String[] lines = ndjson.strip().split("\n");
        assertThat(lines).hasSize(3);

        ObjectMapper mapper = new ObjectMapper();
        var header = mapper.readTree(lines[0]);
        assertThat(header.get("v").asInt()).isEqualTo(1);
        assertThat(header.get("gameType").asText()).isEqualTo("DODGE");
        assertThat(header.get("cols").asInt()).isEqualTo(12);
        assertThat(header.get("rows").asInt()).isEqualTo(16);
        assertThat(header.get("tickMs").asInt()).isEqualTo(100);
        assertThat(header.get("seed").asInt()).isEqualTo(8412739);
        assertThat(header.get("players")).hasSize(2);

        var firstTick = mapper.readTree(lines[1]);
        assertThat(firstTick.get("tick").asInt()).isEqualTo(3);
        assertThat(firstTick.get("moves").get(A).asText()).isEqualTo("LEFT");

        var secondTick = mapper.readTree(lines[2]);
        assertThat(secondTick.get("tick").asInt()).isEqualTo(7);
        assertThat(secondTick.get("moves")).hasSize(2);
    }

    @Test
    void ticksWithNoInputAreNotWritten() {
        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(1, List.of(A), Map.of()),
                Map.of(A, "host")
        );

        assertThat(ndjson.strip().split("\n")).hasSize(1);
    }

    @Test
    void headerCarriesTheRulesSoTheClientCanReproduceThem() throws Exception {
        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(1, List.of(A), Map.of()),
                Map.of(A, "host")
        );

        var header = new ObjectMapper().readTree(ndjson.strip().split("\n")[0]);

        assertThat(header.get("baseSpawn").asDouble()).isEqualTo(0.15);
        assertThat(header.get("spawnStep").asDouble()).isEqualTo(0.05);
        assertThat(header.get("spawnStepTicks").asInt()).isEqualTo(100);
        assertThat(header.get("maxSpawn").asDouble()).isEqualTo(0.60);
        assertThat(header.get("prng").asText()).isEqualTo("xorshift32");
    }
}
