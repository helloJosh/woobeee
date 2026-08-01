package com.woobeee.game.result;

import com.woobeee.game.api.response.GameResultSummaryResponse;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact SQL text {@link GameResultQueryRepository} sends to the database and the
 * parameters it binds, using {@link RecordingDatabaseClient} rather than a mocked
 * {@code DatabaseClient} (see that class's javadoc for why a deep-stub mock cannot model this
 * fluent API faithfully).
 *
 * <p><strong>What this proves, and what it does not:</strong> {@link GameResultControllerTest}
 * mocks {@code GameResultQueryRepository} itself, so it only proves the controller reacts
 * correctly to an empty {@code Mono} — it can't see whether the underlying SQL actually enforces
 * membership. These tests close that gap by asserting the literal SQL string and bound
 * parameters that would be sent to Postgres: if {@code AND p.member_id = :memberId} were ever
 * dropped from {@code FIND_REPLAY_ACCESS}, or {@code WHERE mine.member_id = :memberId} from
 * {@code FIND_BY_MEMBER}, the {@code isEqualTo} text comparison below fails immediately. That is
 * exact-text pinning, not execution: nothing here runs the SQL against a real Postgres instance,
 * so a predicate that is textually present but semantically wrong (e.g. compares the wrong two
 * columns, or uses {@code OR} instead of {@code AND}) would still pass this test. Catching that
 * class of bug needs a live-database integration test (e.g. against the docker-compose Postgres,
 * seeding two members and asserting one cannot read the other's row) — worth adding given this is
 * the exact boundary GAME-AC-22 protects, but a separate, slower test tier from this one.
 */
class GameResultQueryRepositoryTest {

    private static final String FIND_BY_MEMBER_SQL =
            "SELECT r.id AS game_result_id, r.game_type AS game_type, r.ended_at AS ended_at, "
                    + "mine.finish_rank AS finish_rank, COALESCE(winner.display_name, '') AS winner_display_name, "
                    + "(r.replay_object_key IS NOT NULL) AS replay_available "
                    + "FROM game_result_participants mine "
                    + "JOIN game_results r ON r.id = mine.game_result_id "
                    + "LEFT JOIN game_result_participants winner "
                    + "ON winner.game_result_id = r.id AND winner.participant_id = r.winner_participant_id "
                    + "WHERE mine.member_id = :memberId "
                    + "ORDER BY r.ended_at DESC, r.id DESC "
                    + "LIMIT :limit OFFSET :offset";

    private static final String FIND_REPLAY_ACCESS_SQL =
            "SELECT r.replay_object_key AS replay_object_key "
                    + "FROM game_results r "
                    + "JOIN game_result_participants p ON p.game_result_id = r.id "
                    + "WHERE r.id = :gameResultId AND p.member_id = :memberId";

    @Test
    void findByMemberIdIssuesOneJoinedQueryBoundToTheCallingMember() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        GameResultSummaryResponse row = new GameResultSummaryResponse(
                77L, "OMOK", "2026-08-01T00:10:00", 1, "host", true);
        client.stubAll(FIND_BY_MEMBER_SQL, List.of(row));

        StepVerifier.create(new GameResultQueryRepository(client).findByMemberId(11L, 20, 0))
                .expectNext(row)
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        RecordingDatabaseClient.Statement statement = client.onlyStatement();
        assertThat(statement.sql())
                .as("the list query must filter by member inside the join — dropping "
                        + "`mine.member_id = :memberId` would leak every member's match history")
                .isEqualTo(FIND_BY_MEMBER_SQL);
        assertThat(statement.bound()).containsOnly(
                Map.entry("memberId", 11L),
                Map.entry("limit", 20),
                Map.entry("offset", 0)
        );
    }

    /** GAME-AC-22 */
    @Test
    void findReplayAccessIssuesTheMembershipCheckingQuery() {
        RecordingDatabaseClient client = new RecordingDatabaseClient();
        client.stubOne(FIND_REPLAY_ACCESS_SQL,
                new GameResultQueryRepository.ReplayAccess("games/OMOK/77.ndjson"));

        StepVerifier.create(new GameResultQueryRepository(client).findReplayAccess(77L, 11L))
                .expectNextMatches(access -> "games/OMOK/77.ndjson".equals(access.objectKey()))
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        RecordingDatabaseClient.Statement statement = client.onlyStatement();
        assertThat(statement.sql())
                .as("membership must be enforced inside the query — dropping "
                        + "`p.member_id = :memberId` would let any authenticated member fetch any replay")
                .isEqualTo(FIND_REPLAY_ACCESS_SQL);
        assertThat(statement.bound()).containsOnly(
                Map.entry("gameResultId", 77L),
                Map.entry("memberId", 11L)
        );
    }
}
