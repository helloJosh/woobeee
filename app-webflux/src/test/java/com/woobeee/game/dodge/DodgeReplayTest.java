package com.woobeee.game.dodge;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // F1: newline termination is part of the cross-language contract — pin it on the raw,
        // unstripped string, not on a stripped one. Exactly one line per record, exactly one
        // trailing '\n', no leading whitespace.
        assertThat(ndjson).doesNotStartWith("\n").doesNotStartWith(" ");
        assertThat(ndjson).endsWith("\n");
        assertThat(ndjson).doesNotEndWith("\n\n");
        String[] rawLines = ndjson.split("\n", -1);
        assertThat(rawLines).hasSize(4); // header + 2 tick lines + the empty tail after the one closing '\n'
        assertThat(rawLines[3]).isEmpty();

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
        // Minors: pin participantId/displayName values (a swapped mapping must fail) and array
        // ordering (a browser client may key on array position, not on a lookup by id).
        assertThat(header.get("players").get(0).get("participantId").asText()).isEqualTo(A);
        assertThat(header.get("players").get(0).get("displayName").asText()).isEqualTo("host");
        assertThat(header.get("players").get(1).get("participantId").asText()).isEqualTo(B);
        assertThat(header.get("players").get(1).get("displayName").asText()).isEqualTo("손님");

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

        // F1: the empty-game case must be exactly one header line and one trailing newline —
        // pinned on the raw string so a missing or doubled '\n' fails here too.
        assertThat(ndjson).doesNotStartWith("\n").doesNotStartWith(" ");
        assertThat(ndjson).endsWith("\n");
        assertThat(ndjson).doesNotEndWith("\n\n");
        String[] rawLines = ndjson.split("\n", -1);
        assertThat(rawLines).hasSize(2); // header + the empty tail after the one closing '\n'
        assertThat(rawLines[1]).isEmpty();

        assertThat(ndjson.strip().split("\n")).hasSize(1);
    }

    /**
     * F2 — a replay whose recorded inputs never let the game end must not silently return an
     * unfinished {@link DodgeGame}: that would make a caller who forgets to check
     * {@code finished()} treat a corrupted/non-terminating replay as a valid short game.
     * The tick cap is lowered for the test so this does not run for real ticks: obstacles start
     * at row 0 and fall one row per tick, so with two players starting at the bottom row (15)
     * and zero recorded input, nobody can be eliminated within 5 ticks regardless of seed —
     * the cap is guaranteed to be hit first.
     */
    @Test
    void rerunThrowsWhenTheReplayNeverTerminatesWithinTheTickCap() {
        DodgeReplay replay = new DodgeReplay(42, List.of(A, B), Map.of());

        assertThatThrownBy(() -> new DodgeReplayRunner(5).rerun(replay))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("5");
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
