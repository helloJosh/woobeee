package com.woobeee.game.result;

import com.woobeee.game.api.response.GameResultSummaryResponse;
import io.r2dbc.spi.Readable;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 전적 목록은 조인 한 번으로 가져온다 — 루프 안 단건 조회 금지(CLAUDE.md 쿼리 규칙).
 */
@Repository
public class GameResultQueryRepository {
    private static final String FIND_BY_MEMBER =
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

    // GAME-AC-22: 참가자 확인은 이 쿼리 안(:memberId 조인 조건)에서 끝난다 — 조회 후 필터링이 아니다.
    private static final String FIND_REPLAY_ACCESS =
            "SELECT r.replay_object_key AS replay_object_key "
                    + "FROM game_results r "
                    + "JOIN game_result_participants p ON p.game_result_id = r.id "
                    + "WHERE r.id = :gameResultId AND p.member_id = :memberId";

    private final DatabaseClient databaseClient;

    public GameResultQueryRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<GameResultSummaryResponse> findByMemberId(long memberId, int limit, int offset) {
        return databaseClient.sql(FIND_BY_MEMBER)
                .bind("memberId", memberId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map((Readable row) -> new GameResultSummaryResponse(
                        row.get("game_result_id", Long.class),
                        row.get("game_type", String.class),
                        String.valueOf(row.get("ended_at")),
                        row.get("finish_rank", Integer.class),
                        row.get("winner_display_name", String.class),
                        Boolean.TRUE.equals(row.get("replay_available", Boolean.class))
                ))
                .all();
    }

    /** 결과가 비어 있으면 그 회원은 이 게임의 참가자가 아니다. */
    public Mono<ReplayAccess> findReplayAccess(long gameResultId, long memberId) {
        return databaseClient.sql(FIND_REPLAY_ACCESS)
                .bind("gameResultId", gameResultId)
                .bind("memberId", memberId)
                .map((Readable row) -> new ReplayAccess(row.get("replay_object_key", String.class)))
                .one();
    }

    public record ReplayAccess(String objectKey) {
    }
}
