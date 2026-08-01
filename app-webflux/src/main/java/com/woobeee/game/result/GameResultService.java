package com.woobeee.game.result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * 종료된 게임을 남긴다.
 *
 * <p>순서가 계약이다: 결과 행 → 참가자 행 → 기보 업로드 → key 부착. 업로드가 실패하면
 * {@code replay_object_key} 가 null 로 남을 뿐 전적은 그대로다.
 *
 * <p>결과 행과 참가자 행 삽입은 하나의 트랜잭션으로 묶는다 — 참가자 삽입이 실패하면 결과 행도
 * 함께 롤백된다(그렇지 않으면 참가자가 없는 게임 결과가 남아, 해당 참가자는 자신의 기보에도
 * 영구히 접근할 수 없고 전적 조회에서도 누락된다). 기보 업로드와 key 부착은 이 트랜잭션
 * 밖에서 실행한다 — 느린 S3 호출이 DB 트랜잭션을 붙잡고 있어서는 안 되고, 업로드 실패가
 * 이미 커밋된 결과 행을 되돌려서도 안 된다.
 */
@Service
public class GameResultService {
    private final GameResultRepository repository;
    private final ReplayUploader replayUploader;
    private final Clock clock;
    private final TransactionalOperator transactionalOperator;

    public GameResultService(
            GameResultRepository repository,
            ReplayUploader replayUploader,
            Clock clock,
            TransactionalOperator transactionalOperator
    ) {
        this.repository = repository;
        this.replayUploader = replayUploader;
        this.clock = clock;
        this.transactionalOperator = transactionalOperator;
    }

    public Mono<Long> record(FinishedGame game, String replayNdjson) {
        Mono<Long> transactionalInsert = repository.insertResult(game, clock.instant())
                .flatMap(gameResultId -> repository.insertParticipants(gameResultId, game.participants())
                        .thenReturn(gameResultId))
                .as(transactionalOperator::transactional);

        return transactionalInsert.flatMap(gameResultId ->
                replayUploader.upload(game.gameType(), gameResultId, replayNdjson)
                        .flatMap(key -> repository.attachReplayKey(gameResultId, key))
                        .thenReturn(gameResultId));
    }
}
