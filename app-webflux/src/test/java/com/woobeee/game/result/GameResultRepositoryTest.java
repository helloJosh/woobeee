package com.woobeee.game.result;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GameResultRepositoryTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-08-01T00:10:00Z");

    private static final String INSERT_RESULT_SQL =
            "INSERT INTO game_results "
                    + "(game_type, room_id, started_at, ended_at, winner_participant_id, replay_object_key, created_at) "
                    + "VALUES (:gameType, :roomId, :startedAt, :endedAt, :winner, NULL, :createdAt) "
                    + "RETURNING id";

    private static final String INSERT_PARTICIPANT_SQL =
            "INSERT INTO game_result_participants "
                    + "(game_result_id, participant_id, display_name, member_id, finish_rank) "
                    + "VALUES (:gameResultId, :participantId, :displayName, :memberId, :finishRank)";

    private static final String ATTACH_REPLAY_KEY_SQL =
            "UPDATE game_results SET replay_object_key = :key WHERE id = :id";

    private FinishedGame game() {
        return new FinishedGame(
                "OMOK",
                "room-1",
                START,
                END,
                "m:11",
                List.of(
                        new FinishedParticipant("m:11", "host", 11L, 1),
                        new FinishedParticipant("g:a", "손님", null, 2)
                )
        );
    }

    @Test
    void insertResultReturnsTheGeneratedId() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        client.stubOne(INSERT_RESULT_SQL, 77L);

        StepVerifier.create(new GameResultRepository(client).insertResult(game(), END))
                .expectNext(77L)
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void insertResultWritesEveryColumn() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        client.stubOne(INSERT_RESULT_SQL, 77L);

        new GameResultRepository(client).insertResult(game(), END).block(Duration.ofSeconds(2));

        RecordingDatabaseClient.Statement statement = client.onlyStatement();
        assertThat(statement.sql()).isEqualTo(INSERT_RESULT_SQL);
        assertThat(statement.boundNull()).isEmpty();
        assertThat(statement.bound()).containsOnly(
                Map.entry("gameType", "OMOK"),
                Map.entry("roomId", "room-1"),
                Map.entry("startedAt", LocalDateTime.ofInstant(START, ZoneOffset.UTC)),
                Map.entry("endedAt", LocalDateTime.ofInstant(END, ZoneOffset.UTC)),
                Map.entry("winner", "m:11"),
                Map.entry("createdAt", LocalDateTime.ofInstant(END, ZoneOffset.UTC))
        );
    }

    @Test
    void attachReplayKeyUpdatesTheRow() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        client.stubRowsUpdated(ATTACH_REPLAY_KEY_SQL, 1L);

        StepVerifier.create(new GameResultRepository(client).attachReplayKey(77L, "games/OMOK/77.ndjson"))
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        RecordingDatabaseClient.Statement statement = client.onlyStatement();
        assertThat(statement.sql()).isEqualTo(ATTACH_REPLAY_KEY_SQL);
        assertThat(statement.boundNull()).isEmpty();
        assertThat(statement.bound()).containsOnly(
                Map.entry("key", "games/OMOK/77.ndjson"),
                Map.entry("id", 77L)
        );
    }

    @Test
    void insertParticipantsBindsGuestMemberIdAsNull() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        client.stubRowsUpdated(INSERT_PARTICIPANT_SQL, 1L);
        client.stubRowsUpdated(INSERT_PARTICIPANT_SQL, 1L);

        StepVerifier.create(
                        new GameResultRepository(client).insertParticipants(77L, game().participants()))
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        List<RecordingDatabaseClient.Statement> statements = client.executedStatements();
        assertThat(statements).hasSize(2);

        RecordingDatabaseClient.Statement host = statements.get(0);
        assertThat(host.sql()).isEqualTo(INSERT_PARTICIPANT_SQL);
        assertThat(host.boundNull()).isEmpty();
        assertThat(host.bound()).containsOnly(
                Map.entry("gameResultId", 77L),
                Map.entry("participantId", "m:11"),
                Map.entry("displayName", "host"),
                Map.entry("memberId", 11L),
                Map.entry("finishRank", 1)
        );

        RecordingDatabaseClient.Statement guest = statements.get(1);
        assertThat(guest.sql()).isEqualTo(INSERT_PARTICIPANT_SQL);
        assertThat(guest.bound()).containsOnly(
                Map.entry("gameResultId", 77L),
                Map.entry("participantId", "g:a"),
                Map.entry("displayName", "손님"),
                Map.entry("finishRank", 2)
        );
        assertThat(guest.bound()).doesNotContainKey("memberId");
        assertThat(guest.boundNull()).containsOnly(
                Map.entry("memberId", Long.class)
        );
    }
}
