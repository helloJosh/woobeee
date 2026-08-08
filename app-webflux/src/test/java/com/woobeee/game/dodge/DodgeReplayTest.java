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
                .rerun(new DodgeReplay(seed, players, inputs, Map.of()));

        assertThat(replayed.finalRanks()).isEqualTo(original.finalRanks());
        assertThat(replayed.eliminationOrder()).isEqualTo(original.eliminationOrder());
        assertThat(replayed.tick()).isEqualTo(original.tick());
    }

    /**
     * F1 — a game that ends by departure must replay to the same result as the original.
     * {@code eliminate()} mutates game state outside the recorded input stream, so
     * {@link DodgeReplay} carries a {@code departuresByTick} channel alongside
     * {@code inputsByTick}: the tick each departure happened at, in call order. Before this fix
     * (see the removed pre-fix version of this test in the fix report), a replay built from
     * seed + moves alone kept the departed players alive and dodging real obstacles, producing a
     * different winner. Reusing the exact live sequence — no ticks elapse, both non-hosts depart
     * immediately, same as {@code DodgeGameSinkTest.theGameEndsAndRecordsAResult} — pins that the
     * departures channel, not luck, is what makes this deterministic.
     */
    @Test
    void rerunningADepartureEndedReplayReproducesTheOriginalRanks() {
        int seed = 12345;
        List<String> players = List.of(A, B, "g:c");

        DodgeGame original = new DodgeGame(players, seed);
        original.eliminate(B);
        original.eliminate("g:c");

        assertThat(original.finished()).isTrue();
        assertThat(original.tick()).isZero();

        Map<Integer, List<String>> departures = Map.of(0, List.of(B, "g:c"));

        DodgeGame replayed = new DodgeReplayRunner(50)
                .rerun(new DodgeReplay(seed, players, Map.of(), departures));

        assertThat(replayed.finalRanks()).isEqualTo(original.finalRanks());
        assertThat(replayed.eliminationOrder()).isEqualTo(original.eliminationOrder());
        assertThat(replayed.tick()).isEqualTo(original.tick());
    }

    /**
     * F1 — the ndjson carries departures as their own field on the tick line (not a separate
     * line type): a tick can have moves and a departure together (someone moves the same tick a
     * different participant leaves), and one line per tick avoids the reader having to re-merge
     * two lines sharing a tick number.
     */
    @Test
    void ndjsonCarriesDeparturesOnTheirOwnFieldOfTheTickLine() throws Exception {
        Map<Integer, Map<String, Direction>> inputs = new LinkedHashMap<>();
        inputs.put(2, Map.of(A, Direction.LEFT));
        Map<Integer, List<String>> departures = new LinkedHashMap<>();
        departures.put(2, List.of(B));
        departures.put(5, List.of("g:c", "g:d"));

        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(1, List.of(A, B, "g:c", "g:d"), inputs, departures),
                Map.of()
        );

        String[] lines = ndjson.strip().split("\n");
        assertThat(lines).hasSize(3); // header + tick 2 (moves+departure) + tick 5 (departure only)

        ObjectMapper mapper = new ObjectMapper();
        var tick2 = mapper.readTree(lines[1]);
        assertThat(tick2.get("tick").asInt()).isEqualTo(2);
        assertThat(tick2.get("moves").get(A).asText()).isEqualTo("LEFT");
        assertThat(tick2.get("departures").get(0).asText()).isEqualTo(B);

        var tick5 = mapper.readTree(lines[2]);
        assertThat(tick5.get("tick").asInt()).isEqualTo(5);
        assertThat(tick5.has("moves")).isFalse();
        assertThat(tick5.get("departures").get(0).asText()).isEqualTo("g:c");
        assertThat(tick5.get("departures").get(1).asText()).isEqualTo("g:d");
    }

    @Test
    void ndjsonHasAHeaderThenOneLinePerTickWithInput() throws Exception {
        Map<Integer, Map<String, Direction>> inputs = new LinkedHashMap<>();
        inputs.put(3, Map.of(A, Direction.LEFT));
        inputs.put(7, Map.of(A, Direction.UP, B, Direction.RIGHT));

        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(8412739, List.of(A, B), inputs, Map.of()),
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
        assertThat(header.get("v").asInt()).isEqualTo(3);
        assertThat(header.get("gameType").asText()).isEqualTo("DODGE");
        assertThat(header.get("cols").asInt()).isEqualTo(36);
        assertThat(header.get("rows").asInt()).isEqualTo(48);
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
                new DodgeReplay(1, List.of(A), Map.of(), Map.of()),
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
        DodgeReplay replay = new DodgeReplay(42, List.of(A, B), Map.of(), Map.of());

        assertThatThrownBy(() -> new DodgeReplayRunner(5).rerun(replay))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("5");
    }

    /**
     * F1 (fix round 2) — the ndjson format is a cross-language contract: a v1 file meant "read
     * inputsByTick and nothing else." That is no longer true now that departures exist on the
     * tick line, so the header version must move to 2 whenever this writer runs — a v1 reader
     * (written, like the real TypeScript one, straight from a spec that only mentioned moves)
     * would silently drop departures and reproduce F1 client-side: wrong winner, wrong length,
     * no crash, no signal anything was missed. This test exists so a future field addition has
     * to consciously bump this number again instead of leaving a stale contract in place.
     */
    @Test
    void headerVersionIsThreeNowThatTheRulesChanged() {
        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(1, List.of(A), Map.of(), Map.of()),
                Map.of(A, "host")
        );

        var header = new ObjectMapper().readTree(ndjson.strip().split("\n")[0]);

        assertThat(header.get("v").asInt()).isEqualTo(3);
    }

    @Test
    void headerCarriesTheRulesSoTheClientCanReproduceThem() throws Exception {
        String ndjson = new DodgeReplayWriter(new ObjectMapper()).toNdjson(
                new DodgeReplay(1, List.of(A), Map.of(), Map.of()),
                Map.of(A, "host")
        );

        var header = new ObjectMapper().readTree(ndjson.strip().split("\n")[0]);

        assertThat(header.get("baseSpawn").asDouble()).isEqualTo(0.01);
        assertThat(header.get("spawnStep").asDouble()).isEqualTo(0.01);
        assertThat(header.get("spawnStepTicks").asInt()).isEqualTo(100);
        assertThat(header.get("maxSpawn").asDouble()).isEqualTo(0.15);
        assertThat(header.get("fallSpeedStepTicks").asInt()).isEqualTo(300);
        assertThat(header.get("maxFallSpeed").asInt()).isEqualTo(3);
        assertThat(header.get("prng").asText()).isEqualTo("xorshift32");
        assertThat(header.get("playerSize").asInt()).isEqualTo(3);
        assertThat(header.get("moveStep").asInt()).isEqualTo(3);
        assertThat(header.get("spawnSlots").asInt()).isEqualTo(12);
        assertThat(header.get("minObstacleW").asInt()).isEqualTo(2);
        assertThat(header.get("maxObstacleW").asInt()).isEqualTo(5);
        assertThat(header.get("minObstacleH").asInt()).isEqualTo(2);
        assertThat(header.get("maxObstacleH").asInt()).isEqualTo(3);
    }
}
