package com.woobeee.game.result;

import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class GameResultRepository {
    private static final String INSERT_RESULT =
            "INSERT INTO game_results "
                    + "(game_type, room_id, started_at, ended_at, winner_participant_id, replay_object_key, created_at) "
                    + "VALUES (:gameType, :roomId, :startedAt, :endedAt, :winner, NULL, :createdAt) "
                    + "RETURNING id";

    private static final String INSERT_PARTICIPANT =
            "INSERT INTO game_result_participants "
                    + "(game_result_id, participant_id, display_name, member_id, finish_rank) "
                    + "VALUES (:gameResultId, :participantId, :displayName, :memberId, :finishRank)";

    private static final String ATTACH_REPLAY_KEY =
            "UPDATE game_results SET replay_object_key = :key WHERE id = :id";

    private final DatabaseClient databaseClient;

    public GameResultRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Mono<Long> insertResult(FinishedGame game, Instant createdAt) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT_RESULT)
                .bind("gameType", game.gameType())
                .bind("roomId", game.roomId())
                .bind("startedAt", toLocal(game.startedAt()))
                .bind("endedAt", toLocal(game.endedAt()));

        spec = game.winnerParticipantId() == null
                ? spec.bindNull("winner", String.class)
                : spec.bind("winner", game.winnerParticipantId());

        return spec.bind("createdAt", toLocal(createdAt))
                .map((Readable row) -> row.get("id", Long.class))
                .one();
    }

    public Mono<Void> insertParticipants(long gameResultId, List<FinishedParticipant> participants) {
        return Flux.fromIterable(participants)
                .concatMap(participant -> bindParticipant(gameResultId, participant))
                .then();
    }

    public Mono<Void> attachReplayKey(long gameResultId, String objectKey) {
        return databaseClient.sql(ATTACH_REPLAY_KEY)
                .bind("key", objectKey)
                .bind("id", gameResultId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Long> bindParticipant(long gameResultId, FinishedParticipant participant) {
        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(INSERT_PARTICIPANT)
                .bind("gameResultId", gameResultId)
                .bind("participantId", participant.participantId())
                .bind("displayName", participant.displayName())
                .bind("finishRank", participant.finishRank());

        spec = participant.memberId() == null
                ? spec.bindNull("memberId", Long.class)
                : spec.bind("memberId", participant.memberId());

        return spec.fetch().rowsUpdated();
    }

    private LocalDateTime toLocal(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
