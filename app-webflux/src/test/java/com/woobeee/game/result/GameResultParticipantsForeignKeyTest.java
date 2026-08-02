package com.woobeee.game.result;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static io.r2dbc.spi.ConnectionFactoryOptions.DATABASE;
import static io.r2dbc.spi.ConnectionFactoryOptions.DRIVER;
import static io.r2dbc.spi.ConnectionFactoryOptions.HOST;
import static io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD;
import static io.r2dbc.spi.ConnectionFactoryOptions.PORT;
import static io.r2dbc.spi.ConnectionFactoryOptions.USER;

/**
 * G5 known gap: {@code game_result_participants.game_result_id} has no foreign key back to
 * {@code game_results.id} (see {@code V2__game.sql}). A participant row can therefore reference a
 * game result that was never inserted (or has since been deleted), silently corrupting a member's
 * match history and replay access ({@link GameResultQueryRepository}'s joins would simply never
 * match that row, or would appear to reference a game that doesn't exist).
 *
 * <p>This needs a live Postgres, not the {@link RecordingDatabaseClient} the rest of this package
 * uses (that fake only pins SQL text/bindings; it cannot exercise constraints). It talks to the
 * same docker-compose Postgres {@code app-mvc}'s {@code SchemaValidationTest} uses, via a raw R2DBC
 * {@link ConnectionFactory} rather than a full Spring context — there is no R2DBC repository test
 * infrastructure in this module yet, so this is the first one; it is deliberately minimal.
 */
class GameResultParticipantsForeignKeyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final long NON_EXISTENT_GAME_RESULT_ID = -9_999_999L;

    private final ConnectionFactory connectionFactory = ConnectionFactories.get(
            ConnectionFactoryOptions.builder()
                    .option(DRIVER, "postgresql")
                    .option(HOST, "localhost")
                    .option(PORT, 9432)
                    .option(DATABASE, "market")
                    .option(USER, "root")
                    .option(PASSWORD, "123456789")
                    .build());

    private final DatabaseClient client = DatabaseClient.create(connectionFactory);

    @AfterEach
    void cleanUp() {
        client.sql("DELETE FROM game_result_participants WHERE game_result_id = :id")
                .bind("id", NON_EXISTENT_GAME_RESULT_ID)
                .fetch()
                .rowsUpdated()
                .onErrorResume(error -> Mono.just(0L))
                .block(TIMEOUT);
    }

    @Tag("known-gap")
    @DisplayName("G5: a game_result_participants row for a non-existent game_result_id must be rejected")
    @Test
    void insertingAParticipantForAMissingGameResultIsRejected() {
        Mono<Long> insert = client.sql(
                        "INSERT INTO game_result_participants "
                                + "(game_result_id, participant_id, display_name, member_id, finish_rank) "
                                + "VALUES (:gameResultId, :participantId, :displayName, :memberId, :finishRank)")
                .bind("gameResultId", NON_EXISTENT_GAME_RESULT_ID)
                .bind("participantId", "m:no-such-game")
                .bind("displayName", "orphan")
                .bindNull("memberId", Long.class)
                .bind("finishRank", 1)
                .fetch()
                .rowsUpdated();

        // Today there is no foreign key, so this insert quietly succeeds (onComplete with no
        // error) instead of being rejected -- StepVerifier.expectError below is what turns that
        // silent success into an observable failure, pointing straight at the missing constraint.
        StepVerifier.create(insert)
                .expectErrorSatisfies(error -> {
                    String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
                    org.assertj.core.api.Assertions.assertThat(message)
                            .as("insert must fail specifically because of the missing FK on "
                                    + "game_result_id, not for some unrelated reason")
                            .contains("foreign key");
                })
                .verify(TIMEOUT);
    }
}
